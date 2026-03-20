export interface CodeMessage {
  type: string;
  source: string;
  sender: string;
  code: string;
  raw_text: string;
  app_name: string;
  timestamp: number;
  device_id: string;
}

export interface RoomConfig {
  server_base: string;
  room_id: string;
  desktop_token: string;
  pair_code: string;
}

export interface CreateRoomResponse {
  roomId: string;
  pairCode: string;
  desktopToken: string;
}
