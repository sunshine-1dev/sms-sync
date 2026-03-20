export interface Env {
  RELAY_ROOM: DurableObjectNamespace;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    // POST /api/create-room → create a new pairing room, return roomId + pairCode
    if (url.pathname === "/api/create-room" && request.method === "POST") {
      const roomId = crypto.randomUUID();
      const id = env.RELAY_ROOM.idFromName(roomId);
      const room = env.RELAY_ROOM.get(id);
      const resp = await room.fetch(new Request("http://internal/init", { method: "POST" }));
      const data = await resp.json() as { pairCode: string };
      return Response.json({ roomId, pairCode: data.pairCode });
    }

    // POST /api/join-room → Android joins with pairCode, returns roomId + token
    if (url.pathname === "/api/join-room" && request.method === "POST") {
      const body = await request.json() as { pairCode: string };
      if (!body.pairCode || body.pairCode.length !== 6) {
        return Response.json({ error: "invalid pair code" }, { status: 400 });
      }
      // We need to find the room by pair code - use a deterministic name based on code
      const id = env.RELAY_ROOM.idFromName(`pair:${body.pairCode}`);
      const room = env.RELAY_ROOM.get(id);
      const resp = await room.fetch(new Request("http://internal/join", {
        method: "POST",
        body: JSON.stringify({ pairCode: body.pairCode }),
      }));
      return new Response(resp.body, { status: resp.status, headers: resp.headers });
    }

    // WebSocket /ws?roomId=xxx&role=android|desktop&token=xxx
    if (url.pathname === "/ws") {
      const roomId = url.searchParams.get("roomId");
      const role = url.searchParams.get("role");
      if (!roomId || !role) {
        return Response.json({ error: "missing roomId or role" }, { status: 400 });
      }
      const id = env.RELAY_ROOM.idFromName(roomId);
      const room = env.RELAY_ROOM.get(id);
      return room.fetch(request);
    }

    // Health check
    if (url.pathname === "/") {
      return Response.json({ status: "ok", service: "sms-sync-relay" });
    }

    return Response.json({ error: "not found" }, { status: 404 });
  },
};

// ─── Durable Object: RelayRoom ───

interface RoomState {
  pairCode: string;
  token: string;
  createdAt: number;
}

interface Client {
  ws: WebSocket;
  role: string;
  authed: boolean;
}

export class RelayRoom {
  private state: DurableObjectState;
  private clients: Map<WebSocket, Client> = new Map();
  private roomData: RoomState | null = null;
  private codeCache: any[] = [];

  constructor(state: DurableObjectState) {
    this.state = state;
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/init") {
      return this.handleInit();
    }

    if (url.pathname === "/join") {
      return this.handleJoin(request);
    }

    if (url.pathname === "/ws") {
      return this.handleWebSocket(request);
    }

    return Response.json({ error: "not found" }, { status: 404 });
  }

  private async handleInit(): Promise<Response> {
    const pairCode = String(Math.floor(100000 + Math.random() * 900000));
    const token = crypto.randomUUID();

    this.roomData = { pairCode, token, createdAt: Date.now() };
    await this.state.storage.put("room", this.roomData);

    // Also store a mapping so we can find this room by pair code
    // We do this by creating a separate DO keyed by "pair:<code>"
    // But since this DO IS the room, we store the roomId for lookup

    return Response.json({ pairCode });
  }

  private async handleJoin(request: Request): Promise<Response> {
    const body = await request.json() as { pairCode: string };

    if (!this.roomData) {
      this.roomData = await this.state.storage.get("room") as RoomState | undefined ?? null;
    }

    if (!this.roomData || this.roomData.pairCode !== body.pairCode) {
      return Response.json({ error: "invalid pair code" }, { status: 403 });
    }

    const androidToken = crypto.randomUUID();
    await this.state.storage.put("androidToken", androidToken);

    return Response.json({
      success: true,
      token: androidToken,
      roomId: this.state.id.toString(),
    });
  }

  private async handleWebSocket(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const role = url.searchParams.get("role") || "unknown";
    const token = url.searchParams.get("token") || "";

    const pair = new WebSocketPair();
    const [client, server] = [pair[0], pair[1]];

    this.state.acceptWebSocket(server);

    const clientInfo: Client = { ws: server, role, authed: false };

    // Auto-auth for desktop (room creator) or with valid token
    if (!this.roomData) {
      this.roomData = await this.state.storage.get("room") as RoomState | undefined ?? null;
    }

    if (this.roomData && token === this.roomData.token) {
      clientInfo.authed = true;
    }

    const savedAndroidToken = await this.state.storage.get("androidToken") as string | undefined;
    if (savedAndroidToken && token === savedAndroidToken) {
      clientInfo.authed = true;
    }

    this.clients.set(server, clientInfo);

    // Send cached codes to desktop on connect
    if (role === "desktop" && clientInfo.authed) {
      for (const code of this.codeCache) {
        server.send(JSON.stringify(code));
      }
    }

    server.addEventListener("message", (event) => {
      this.handleMessage(server, event.data as string);
    });

    server.addEventListener("close", () => {
      this.clients.delete(server);
    });

    server.addEventListener("error", () => {
      this.clients.delete(server);
    });

    return new Response(null, { status: 101, webSocket: client });
  }

  private handleMessage(sender: WebSocket, raw: string) {
    let msg: any;
    try {
      msg = JSON.parse(raw);
    } catch {
      return;
    }

    const client = this.clients.get(sender);
    if (!client) return;

    switch (msg.type) {
      case "code":
        if (!client.authed) {
          sender.send(JSON.stringify({ type: "error", error: "not authenticated" }));
          return;
        }
        // Cache the code (keep last 20, expire after 5 min)
        msg._cachedAt = Date.now();
        this.codeCache.push(msg);
        this.codeCache = this.codeCache
          .filter((c) => Date.now() - c._cachedAt < 5 * 60 * 1000)
          .slice(-20);

        // Forward to all desktop clients
        for (const [ws, info] of this.clients) {
          if (info.role === "desktop" && ws !== sender) {
            try {
              ws.send(raw);
            } catch {}
          }
        }
        break;

      case "ping":
        sender.send(JSON.stringify({ type: "pong" }));
        break;
    }
  }

  // Called by the runtime for hibernated WebSocket messages
  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer) {
    if (typeof message === "string") {
      this.handleMessage(ws, message);
    }
  }

  async webSocketClose(ws: WebSocket) {
    this.clients.delete(ws);
  }

  async webSocketError(ws: WebSocket) {
    this.clients.delete(ws);
  }
}
