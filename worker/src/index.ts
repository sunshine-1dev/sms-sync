export interface Env {
  RELAY_ROOM: DurableObjectNamespace;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const headers = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "GET,POST,OPTIONS", "Access-Control-Allow-Headers": "Content-Type" };

    if (request.method === "OPTIONS") {
      return new Response(null, { headers });
    }

    // POST /api/create-room → desktop creates room
    if (url.pathname === "/api/create-room" && request.method === "POST") {
      const roomId = crypto.randomUUID();
      const id = env.RELAY_ROOM.idFromName(roomId);
      const room = env.RELAY_ROOM.get(id);
      const resp = await room.fetch(new Request("http://internal/init", { method: "POST" }));
      const data = (await resp.json()) as { pairCode: string; desktopToken: string };

      // Register pairCode → roomId in lookup DO
      const lookupId = env.RELAY_ROOM.idFromName("__lookup__");
      const lookupDO = env.RELAY_ROOM.get(lookupId);
      await lookupDO.fetch(new Request("http://internal/register", {
        method: "POST",
        body: JSON.stringify({ pairCode: data.pairCode, roomId }),
      }));

      return Response.json({ roomId, ...data }, { headers });
    }

    // POST /api/pair → Android pairs with code
    if (url.pathname === "/api/pair" && request.method === "POST") {
      const body = (await request.json()) as { pairCode: string; deviceId: string };
      if (!body.pairCode || body.pairCode.length !== 6) {
        return Response.json({ error: "配对码无效" }, { status: 400, headers });
      }

      // Lookup roomId by pairCode
      const lookupId = env.RELAY_ROOM.idFromName("__lookup__");
      const lookupDO = env.RELAY_ROOM.get(lookupId);
      const lookupResp = await lookupDO.fetch(new Request("http://internal/lookup", {
        method: "POST",
        body: JSON.stringify({ pairCode: body.pairCode }),
      }));
      if (lookupResp.status !== 200) {
        return Response.json({ error: "配对码不存在或已过期" }, { status: 403, headers });
      }
      const { roomId } = (await lookupResp.json()) as { roomId: string };

      // Pair android in the room DO
      const roomDOId = env.RELAY_ROOM.idFromName(roomId);
      const roomDO = env.RELAY_ROOM.get(roomDOId);
      const pairResp = await roomDO.fetch(new Request("http://internal/pair-android", {
        method: "POST",
        body: JSON.stringify({ deviceId: body.deviceId, roomId }),
      }));
      const pairData = await pairResp.json();
      return Response.json(pairData, { status: pairResp.status, headers });
    }

    // GET /api/room-info?roomId=xxx&token=xxx → desktop polls pair status
    if (url.pathname === "/api/room-info" && request.method === "GET") {
      const roomId = url.searchParams.get("roomId");
      const token = url.searchParams.get("token");
      if (!roomId || !token) {
        return Response.json({ error: "missing params" }, { status: 400, headers });
      }
      const id = env.RELAY_ROOM.idFromName(roomId);
      const room = env.RELAY_ROOM.get(id);
      const resp = await room.fetch(new Request(`http://internal/info?token=${token}`));
      const data = await resp.json();
      return Response.json(data, { status: resp.status, headers });
    }

    // POST /api/send-code → Android HTTP fallback when WebSocket is down
    if (url.pathname === "/api/send-code" && request.method === "POST") {
      const body = (await request.json()) as { roomId: string; token: string; message: any };
      if (!body.roomId || !body.token || !body.message) {
        return Response.json({ error: "missing params" }, { status: 400, headers });
      }
      const id = env.RELAY_ROOM.idFromName(body.roomId);
      const room = env.RELAY_ROOM.get(id);
      const resp = await room.fetch(new Request("http://internal/forward", {
        method: "POST",
        body: JSON.stringify({ token: body.token, message: body.message }),
      }));
      const data = await resp.json();
      return Response.json(data, { status: resp.status, headers });
    }

    // WebSocket: /ws?roomId=xxx&token=xxx&role=desktop|android
    if (url.pathname === "/ws") {
      const roomId = url.searchParams.get("roomId");
      if (!roomId) {
        return Response.json({ error: "missing roomId" }, { status: 400, headers });
      }
      const id = env.RELAY_ROOM.idFromName(roomId);
      const room = env.RELAY_ROOM.get(id);
      return room.fetch(request);
    }

    if (url.pathname === "/") {
      return Response.json({ status: "ok", service: "sms-sync-relay" }, { headers });
    }

    return Response.json({ error: "not found" }, { status: 404, headers });
  },
};

// ─── Durable Object: RelayRoom ───

interface Client {
  role: string;
}

export class RelayRoom {
  private state: DurableObjectState;
  private clients: Map<WebSocket, Client> = new Map();
  private codeCache: any[] = [];
  // Dedup: track recently forwarded code keys (code+timestamp) for 60 seconds
  private recentIds: Map<string, number> = new Map();

  constructor(state: DurableObjectState) {
    this.state = state;
  }

  /** Returns true if this code was already forwarded recently */
  private isDuplicate(msg: any): boolean {
    const key = `${msg.code}:${msg.timestamp}`;
    const now = Date.now();
    // Clean old entries
    for (const [k, t] of this.recentIds) {
      if (now - t > 60_000) this.recentIds.delete(k);
    }
    if (this.recentIds.has(key)) return true;
    this.recentIds.set(key, now);
    return false;
  }

  /** Cache a code and forward to all desktop WebSockets */
  private forwardToDesktops(msg: any, excludeWs?: WebSocket): number {
    msg._cachedAt = Date.now();
    this.codeCache.push(msg);
    this.codeCache = this.codeCache
      .filter((c: any) => Date.now() - c._cachedAt < 5 * 60 * 1000)
      .slice(-20);

    const raw = JSON.stringify(msg);
    const desktopSockets = this.state.getWebSockets("desktop");
    let sent = 0;
    for (const ws of desktopSockets) {
      if (ws !== excludeWs) {
        try { ws.send(raw); sent++; } catch {}
      }
    }
    return sent;
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    switch (url.pathname) {
      case "/init": return this.handleInit();
      case "/register": return this.handleRegister(request);
      case "/lookup": return this.handleLookup(request);
      case "/pair-android": return this.handlePairAndroid(request);
      case "/info": return this.handleInfo(request);
      case "/forward": return this.handleForward(request);
      case "/ws": return this.handleWebSocket(request);
      default: return Response.json({ error: "not found" }, { status: 404 });
    }
  }

  // ── Room init ──
  private async handleInit(): Promise<Response> {
    const pairCode = String(Math.floor(100000 + Math.random() * 900000));
    const desktopToken = crypto.randomUUID();
    await this.state.storage.put("desktopToken", desktopToken);
    await this.state.storage.put("pairCode", pairCode);
    return Response.json({ pairCode, desktopToken });
  }

  // ── Lookup DO: register pairCode → roomId ──
  private async handleRegister(request: Request): Promise<Response> {
    const { pairCode, roomId } = (await request.json()) as { pairCode: string; roomId: string };
    await this.state.storage.put(`code:${pairCode}`, roomId);
    // Auto-expire: set alarm to clean up after 30 minutes
    return Response.json({ ok: true });
  }

  // ── Lookup DO: find roomId by pairCode ──
  private async handleLookup(request: Request): Promise<Response> {
    const { pairCode } = (await request.json()) as { pairCode: string };
    const roomId = await this.state.storage.get(`code:${pairCode}`);
    if (!roomId) {
      return Response.json({ error: "not found" }, { status: 404 });
    }
    return Response.json({ roomId });
  }

  // ── Android pairing ──
  private async handlePairAndroid(request: Request): Promise<Response> {
    const { deviceId, roomId } = (await request.json()) as { deviceId: string; roomId: string };
    const androidToken = crypto.randomUUID();
    await this.state.storage.put("androidToken", androidToken);
    await this.state.storage.put("androidDeviceId", deviceId);
    return Response.json({ success: true, token: androidToken, roomId });
  }

  // ── Room info (for desktop to check pair status) ──
  private async handleInfo(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const token = url.searchParams.get("token");
    const desktopToken = await this.state.storage.get("desktopToken");
    if (token !== desktopToken) {
      return Response.json({ error: "unauthorized" }, { status: 403 });
    }
    const paired = !!(await this.state.storage.get("androidToken"));
    const pairCode = await this.state.storage.get("pairCode");
    return Response.json({ paired, pairCode });
  }

  // ── HTTP forward (Android fallback when WS is down) ──
  private async handleForward(request: Request): Promise<Response> {
    const { token, message } = (await request.json()) as { token: string; message: any };
    const androidToken = await this.state.storage.get("androidToken");
    if (token !== androidToken) {
      return Response.json({ error: "unauthorized" }, { status: 403 });
    }

    if (this.isDuplicate(message)) {
      return Response.json({ success: true, sent: 0, deduplicated: true });
    }

    const sent = this.forwardToDesktops(message);
    return Response.json({ success: true, sent });
  }

  // ── WebSocket ──
  private async handleWebSocket(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const role = url.searchParams.get("role") || "unknown";
    const token = url.searchParams.get("token") || "";

    const desktopToken = await this.state.storage.get("desktopToken");
    const androidToken = await this.state.storage.get("androidToken");

    if (token !== desktopToken && token !== androidToken) {
      return Response.json({ error: "unauthorized" }, { status: 403 });
    }

    const pair = new WebSocketPair();
    const [clientSide, serverSide] = [pair[0], pair[1]];

    this.state.acceptWebSocket(serverSide, [role]);
    this.clients.set(serverSide, { role });

    // Send cached codes to new desktop connection
    if (role === "desktop") {
      for (const code of this.codeCache) {
        serverSide.send(JSON.stringify(code));
      }
    }

    return new Response(null, { status: 101, webSocket: clientSide });
  }

  // ── Hibernation API ──
  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer) {
    if (typeof message !== "string") return;
    let msg: any;
    try { msg = JSON.parse(message); } catch { return; }

    switch (msg.type) {
      case "code": {
        if (this.isDuplicate(msg)) break;
        this.forwardToDesktops(msg, ws);
        break;
      }
      case "ping":
        ws.send(JSON.stringify({ type: "pong" }));
        break;
    }
  }

  async webSocketClose(ws: WebSocket) { this.clients.delete(ws); }
  async webSocketError(ws: WebSocket) { this.clients.delete(ws); }
}
