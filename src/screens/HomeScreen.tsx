import React, {useState, useCallback, useEffect} from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  Linking,
  Alert,
  ScrollView,
} from 'react-native';
import Clipboard from '@react-native-clipboard/clipboard';
import {ProxyModule} from '../native/ProxyModule';
import {useProxyStatus} from '../hooks/useProxyStatus';
import {useSettings} from '../hooks/useSettings';
import {humanBytes, generateProxyLink} from '../utils/proxyLink';
import type {NativeStackNavigationProp} from '@react-navigation/native-stack';

type Props = {
  navigation: NativeStackNavigationProp<any>;
};

export default function HomeScreen({navigation}: Props) {
  const stats = useProxyStatus(1500);
  const {config} = useSettings();
  const [running, setRunning] = useState(false);

  const isRunning = stats?.isRunning || running;

  useEffect(() => {
    if (stats?.isRunning !== undefined) {
      setRunning(stats.isRunning);
    }
  }, [stats?.isRunning]);

  const handleToggle = useCallback(async () => {
    try {
      if (running) {
        await ProxyModule.stopProxy();
        setRunning(false);
      } else {
        // Request battery optimizations before starting
        await ProxyModule.checkAndRequestBatteryOptimizations().catch(() => {});
        await ProxyModule.startProxy(config);
        setRunning(true);
      }
    } catch (e: any) {
      Alert.alert('Error', e.message || 'Unknown error');
    }
  }, [running, config]);

  const handleOpenTelegram = useCallback(() => {
    const link = generateProxyLink(config);
    Linking.openURL(link).catch((err) => {
      console.error(err);
    });
  }, [config]);

  const handleCopyLink = useCallback(() => {
    const link = generateProxyLink(config);
    Clipboard.setString(link);
  }, [config]);

  const poolTotal = (stats?.poolHits || 0) + (stats?.poolMisses || 0);
  const poolRate =
    poolTotal > 0
      ? `${stats?.poolHits || 0}/${poolTotal}`
      : 'n/a';

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <View style={styles.statusCard}>
        <View
          style={[
            styles.statusDot,
            {backgroundColor: isRunning ? '#4CAF50' : '#F44336'},
          ]}
        />
        <Text style={styles.statusText}>
          {isRunning ? 'Proxy Running' : 'Proxy Stopped'}
        </Text>
        {isRunning && (
          <Text style={styles.statusSub}>
            {config.host}:{config.port}
          </Text>
        )}
      </View>

      <TouchableOpacity
        style={[styles.button, isRunning ? styles.stopButton : styles.startButton]}
        onPress={handleToggle}>
        <Text style={styles.buttonText}>
          {isRunning ? 'Stop Proxy' : 'Start Proxy'}
        </Text>
      </TouchableOpacity>

      {isRunning && (
        <>
          <View style={styles.linkRow}>
            <TouchableOpacity style={styles.linkButton} onPress={handleOpenTelegram}>
              <Text style={styles.linkButtonText}>Open in Telegram</Text>
            </TouchableOpacity>
            <TouchableOpacity style={styles.linkButton} onPress={handleCopyLink}>
              <Text style={styles.linkButtonText}>Copy Link</Text>
            </TouchableOpacity>
          </View>

          <View style={styles.statsCard}>
            <Text style={styles.statsTitle}>Statistics</Text>
            <StatRow label="Active" value={String(stats?.connectionsActive || 0)} />
            <StatRow label="Total" value={String(stats?.connectionsTotal || 0)} />
            <StatRow label="WebSocket" value={String(stats?.connectionsWs || 0)} />
            <StatRow label="TCP Fallback" value={String(stats?.connectionsTcpFallback || 0)} />
            <StatRow label="CF Proxy" value={String(stats?.connectionsCfProxy || 0)} />
            <StatRow label="Bad" value={String(stats?.connectionsBad || 0)} />
            <StatRow label="WS Errors" value={String(stats?.wsErrors || 0)} />
            <StatRow label="Upload" value={humanBytes(stats?.bytesUp || 0)} />
            <StatRow label="Download" value={humanBytes(stats?.bytesDown || 0)} />
            <StatRow label="Pool Hit Rate" value={poolRate} />
          </View>
        </>
      )}

      <View style={styles.navRow}>
        <TouchableOpacity
          style={styles.navButton}
          onPress={() => navigation.navigate('Settings')}>
          <Text style={styles.navButtonText}>Settings</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={styles.navButton}
          onPress={() => navigation.navigate('Logs')}>
          <Text style={styles.navButtonText}>Logs</Text>
        </TouchableOpacity>
      </View>
    </ScrollView>
  );
}

function StatRow({label, value}: {label: string; value: string}) {
  return (
    <View style={styles.statRow}>
      <Text style={styles.statLabel}>{label}</Text>
      <Text style={styles.statValue}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: '#121212'},
  content: {padding: 20},
  statusCard: {
    alignItems: 'center',
    padding: 30,
    backgroundColor: '#1E1E1E',
    borderRadius: 16,
    marginBottom: 20,
  },
  statusDot: {width: 20, height: 20, borderRadius: 10, marginBottom: 12},
  statusText: {color: '#FFFFFF', fontSize: 22, fontWeight: '700'},
  statusSub: {color: '#888888', fontSize: 14, marginTop: 4},
  button: {
    padding: 16,
    borderRadius: 12,
    alignItems: 'center',
    marginBottom: 16,
  },
  startButton: {backgroundColor: '#0088CC'},
  stopButton: {backgroundColor: '#F44336'},
  buttonText: {color: '#FFFFFF', fontSize: 18, fontWeight: '600'},
  linkRow: {flexDirection: 'row', gap: 12, marginBottom: 20},
  linkButton: {
    flex: 1,
    padding: 12,
    backgroundColor: '#1E1E1E',
    borderRadius: 10,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#333',
  },
  linkButtonText: {color: '#0088CC', fontSize: 14, fontWeight: '600'},
  statsCard: {
    backgroundColor: '#1E1E1E',
    borderRadius: 16,
    padding: 16,
    marginBottom: 20,
  },
  statsTitle: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '700',
    marginBottom: 12,
  },
  statRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 6,
    borderBottomWidth: 1,
    borderBottomColor: '#2A2A2A',
  },
  statLabel: {color: '#999999', fontSize: 14},
  statValue: {color: '#FFFFFF', fontSize: 14, fontWeight: '500'},
  navRow: {flexDirection: 'row', gap: 12},
  navButton: {
    flex: 1,
    padding: 14,
    backgroundColor: '#1E1E1E',
    borderRadius: 10,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#333',
  },
  navButtonText: {color: '#FFFFFF', fontSize: 14, fontWeight: '500'},
});
