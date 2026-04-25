import React from 'react';
import {View, Text, TouchableOpacity, StyleSheet, Linking} from 'react-native';
import Clipboard from '@react-native-clipboard/clipboard';
import {generateProxyLink} from '../utils/proxyLink';
import type {ProxyConfig} from '../types/proxy';

type Props = {
  config: ProxyConfig;
  onDone: () => void;
};

export default function FirstRunScreen({config, onDone}: Props) {
  const link = generateProxyLink(config);

  const handleOpenTelegram = () => {
    Linking.openURL(link).catch(() => {});
    onDone();
  };

  const handleCopyAndDone = () => {
    Clipboard.setString(link);
    onDone();
  };

  return (
    <View style={styles.container}>
      <Text style={styles.title}>TG WS Proxy</Text>
      <Text style={styles.subtitle}>Local MTProto WebSocket Proxy</Text>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>How to connect</Text>

        <Text style={styles.step}>
          1. Tap "Open in Telegram" below to auto-configure
        </Text>
        <Text style={styles.step}>
          2. Or go to Telegram Settings {'>'} Advanced {'>'} Connection Type {'>'} Proxy
        </Text>
        <Text style={styles.step}>3. Add MTProto proxy:</Text>

        <View style={styles.infoRow}>
          <Text style={styles.infoLabel}>Server:</Text>
          <Text style={styles.infoValue}>{config.host}</Text>
        </View>
        <View style={styles.infoRow}>
          <Text style={styles.infoLabel}>Port:</Text>
          <Text style={styles.infoValue}>{config.port}</Text>
        </View>
        <View style={styles.infoRow}>
          <Text style={styles.infoLabel}>Secret:</Text>
          <Text style={styles.infoValueSmall}>{config.secret}</Text>
        </View>
      </View>

      <TouchableOpacity style={styles.primaryButton} onPress={handleOpenTelegram}>
        <Text style={styles.primaryText}>Open in Telegram</Text>
      </TouchableOpacity>

      <TouchableOpacity style={styles.secondaryButton} onPress={handleCopyAndDone}>
        <Text style={styles.secondaryText}>Copy Link & Continue</Text>
      </TouchableOpacity>

      <TouchableOpacity style={styles.skipButton} onPress={onDone}>
        <Text style={styles.skipText}>Skip</Text>
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#121212',
    padding: 24,
    justifyContent: 'center',
  },
  title: {
    color: '#FFF',
    fontSize: 28,
    fontWeight: '800',
    textAlign: 'center',
  },
  subtitle: {
    color: '#888',
    fontSize: 14,
    textAlign: 'center',
    marginBottom: 30,
    marginTop: 4,
  },
  card: {
    backgroundColor: '#1E1E1E',
    borderRadius: 16,
    padding: 20,
    marginBottom: 24,
  },
  cardTitle: {
    color: '#0088CC',
    fontSize: 16,
    fontWeight: '700',
    marginBottom: 16,
  },
  step: {color: '#CCC', fontSize: 14, lineHeight: 22, marginBottom: 4},
  infoRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 6,
    borderTopWidth: 1,
    borderTopColor: '#2A2A2A',
    marginTop: 4,
  },
  infoLabel: {color: '#999', fontSize: 14},
  infoValue: {color: '#FFF', fontSize: 14, fontWeight: '600'},
  infoValueSmall: {
    color: '#FFF',
    fontSize: 11,
    fontWeight: '500',
    fontFamily: 'monospace',
    maxWidth: 200,
  },
  primaryButton: {
    backgroundColor: '#0088CC',
    padding: 16,
    borderRadius: 12,
    alignItems: 'center',
    marginBottom: 12,
  },
  primaryText: {color: '#FFF', fontSize: 16, fontWeight: '700'},
  secondaryButton: {
    backgroundColor: '#1E1E1E',
    padding: 14,
    borderRadius: 12,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#333',
    marginBottom: 12,
  },
  secondaryText: {color: '#0088CC', fontSize: 15, fontWeight: '600'},
  skipButton: {alignItems: 'center', padding: 10},
  skipText: {color: '#666', fontSize: 14},
});
