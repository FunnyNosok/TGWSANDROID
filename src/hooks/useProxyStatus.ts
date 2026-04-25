import {useState, useEffect, useRef} from 'react';
import {ProxyModule} from '../native/ProxyModule';
import type {ProxyStats} from '../types/proxy';

export function useProxyStatus(intervalMs = 1500) {
  const [stats, setStats] = useState<ProxyStats | null>(null);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    const poll = async () => {
      try {
        const s = await ProxyModule.getStats();
        setStats(s);
      } catch {}
    };

    poll();
    intervalRef.current = setInterval(poll, intervalMs);

    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, [intervalMs]);

  return stats;
}
