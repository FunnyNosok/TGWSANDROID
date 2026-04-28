import type {ProxyConfig} from '../types/proxy';

export const DEFAULT_CONFIG: ProxyConfig = {
  host: '127.0.0.1',
  port: 1443,
  secret: '',
  dcIps: ['2:149.154.167.220', '4:149.154.167.220'],
  bufferSizeKb: 256,
  poolSize: 4,
  cfProxy: true,
  cfProxyPriority: true,
  cfProxyUserDomain: '',
  dpiBypass: false,
};

export const CONFIG_STORAGE_KEY = '@tgwsproxy_config';
export const FIRST_RUN_KEY = '@tgwsproxy_first_run';
