import type {ProxyConfig} from '../types/proxy';

export function generateProxyLink(config: ProxyConfig): string {
  const host = config.host === '0.0.0.0' ? '127.0.0.1' : config.host;
  return `tg://proxy?server=${host}&port=${config.port}&secret=dd${config.secret}`;
}

export function humanBytes(n: number): string {
  let value = n;
  for (const unit of ['B', 'KB', 'MB', 'GB']) {
    if (Math.abs(value) < 1024) {
      return `${value.toFixed(1)}${unit}`;
    }
    value /= 1024;
  }
  return `${value.toFixed(1)}TB`;
}
