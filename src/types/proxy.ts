export interface ProxyConfig {
  host: string;
  port: number;
  secret: string;
  dcIps: string[];
  bufferSizeKb: number;
  poolSize: number;
  cfProxy: boolean;
  cfProxyPriority: boolean;
  cfProxyUserDomain: string;
}

export interface ProxyStats {
  connectionsTotal: number;
  connectionsActive: number;
  connectionsWs: number;
  connectionsTcpFallback: number;
  connectionsCfProxy: number;
  connectionsBad: number;
  connectionsMasked: number;
  wsErrors: number;
  bytesUp: number;
  bytesDown: number;
  poolHits: number;
  poolMisses: number;
  isRunning: boolean;
}
