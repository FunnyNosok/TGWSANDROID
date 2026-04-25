import {NativeModules} from 'react-native';
import type {ProxyConfig, ProxyStats} from '../types/proxy';

const {ProxyModule: NativeProxy} = NativeModules;

export const ProxyModule = {
  startProxy: (config: ProxyConfig): Promise<boolean> =>
    NativeProxy.startProxy(config),

  stopProxy: (): Promise<boolean> => NativeProxy.stopProxy(),

  getStats: (): Promise<ProxyStats> => NativeProxy.getStats(),

  isRunning: (): Promise<boolean> => NativeProxy.isRunning(),

  getProxyLink: (config: ProxyConfig): Promise<string> =>
    NativeProxy.getProxyLink(config),

  getLogs: (): Promise<string[]> => NativeProxy.getLogs(),
};
