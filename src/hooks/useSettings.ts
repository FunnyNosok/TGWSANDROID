import {useState, useEffect, useCallback} from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import type {ProxyConfig} from '../types/proxy';
import {DEFAULT_CONFIG, CONFIG_STORAGE_KEY} from '../utils/constants';

function generateSecret(): string {
  const bytes = new Array(16);
  for (let i = 0; i < 16; i++) {
    bytes[i] = Math.floor(Math.random() * 256);
  }
  return bytes.map(b => b.toString(16).padStart(2, '0')).join('');
}

export function useSettings() {
  const [config, setConfig] = useState<ProxyConfig>({
    ...DEFAULT_CONFIG,
    secret: generateSecret(),
  });
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    (async () => {
      try {
        const stored = await AsyncStorage.getItem(CONFIG_STORAGE_KEY);
        if (stored) {
          const parsed = JSON.parse(stored);
          setConfig({...DEFAULT_CONFIG, ...parsed});
        } else {
          const initial = {...DEFAULT_CONFIG, secret: generateSecret()};
          await AsyncStorage.setItem(CONFIG_STORAGE_KEY, JSON.stringify(initial));
          setConfig(initial);
        }
      } catch {
        setConfig({...DEFAULT_CONFIG, secret: generateSecret()});
      }
      setLoaded(true);
    })();
  }, []);

  const saveConfig = useCallback(async (newConfig: ProxyConfig) => {
    setConfig(newConfig);
    await AsyncStorage.setItem(CONFIG_STORAGE_KEY, JSON.stringify(newConfig));
  }, []);

  return {config, saveConfig, loaded};
}
