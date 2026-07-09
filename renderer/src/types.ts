export interface Account {
  id: string;
  username: string;
  type: 'microsoft' | 'offline';
  createdAt: string;
}

export interface ResourcePack {
  id: string;
  name: string;
  description: string;
  size: string;
  isActive: boolean;
  version: string;
}

export interface ClientLogs {
  timestamp: string;
  level: 'INFO' | 'WARN' | 'ERROR';
  thread: string;
  message: string;
}

export interface MinecraftServer {
  id: string;
  name: string;
  address: string;
  ping: number;
  players: string;
  maxPlayers: string;
  status: 'online' | 'offline';
  description: string;
}

