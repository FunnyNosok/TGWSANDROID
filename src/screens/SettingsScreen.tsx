import React, {useState, useEffect} from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  ScrollView,
  Switch,
  Alert,
} from 'react-native';
import {useSettings} from '../hooks/useSettings';
import type {ProxyConfig} from '../types/proxy';
import type {NativeStackNavigationProp} from '@react-navigation/native-stack';

type Props = {
  navigation: NativeStackNavigationProp<any>;
};

export default function SettingsScreen({navigation}: Props) {
  const {config, saveConfig, loaded} = useSettings();
  const [draft, setDraft] = useState<ProxyConfig>(config);

  useEffect(() => {
    if (loaded) setDraft(config);
  }, [loaded, config]);

  const update = (key: keyof ProxyConfig, value: any) => {
    setDraft(prev => ({...prev, [key]: value}));
  };

  const handleSave = async () => {
    if (!draft.secret || draft.secret.length !== 32) {
      Alert.alert('Error', 'Secret must be exactly 32 hex characters');
      return;
    }
    if (draft.port < 1 || draft.port > 65535) {
      Alert.alert('Error', 'Port must be between 1 and 65535');
      return;
    }
    await saveConfig(draft);
    Alert.alert('Saved', 'Settings saved. Restart proxy to apply changes.', [
      {text: 'OK', onPress: () => navigation.goBack()},
    ]);
  };

  const regenerateSecret = () => {
    const bytes = new Array(16);
    for (let i = 0; i < 16; i++) bytes[i] = Math.floor(Math.random() * 256);
    update('secret', bytes.map(b => b.toString(16).padStart(2, '0')).join(''));
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.sectionTitle}>Network</Text>

      <FieldLabel label="Host" />
      <TextInput
        style={styles.input}
        value={draft.host}
        onChangeText={v => update('host', v)}
        placeholderTextColor="#666"
      />

      <FieldLabel label="Port" />
      <TextInput
        style={styles.input}
        value={String(draft.port)}
        onChangeText={v => update('port', parseInt(v) || 0)}
        keyboardType="numeric"
        placeholderTextColor="#666"
      />

      <FieldLabel label="Secret" />
      <View style={styles.secretRow}>
        <TextInput
          style={[styles.input, {flex: 1}]}
          value={draft.secret}
          onChangeText={v => update('secret', v)}
          placeholderTextColor="#666"
          autoCapitalize="none"
        />
        <TouchableOpacity style={styles.regenButton} onPress={regenerateSecret}>
          <Text style={styles.regenText}>New</Text>
        </TouchableOpacity>
      </View>

      <FieldLabel label="DC -> IP (one per line, format DC:IP)" />
      <TextInput
        style={[styles.input, {height: 80, textAlignVertical: 'top'}]}
        value={draft.dcIps.join('\n')}
        onChangeText={v => update('dcIps', v.split('\n').filter(s => s.trim()))}
        multiline
        placeholderTextColor="#666"
      />

      <Text style={styles.sectionTitle}>Performance</Text>

      <FieldLabel label="Buffer Size (KB)" />
      <TextInput
        style={styles.input}
        value={String(draft.bufferSizeKb)}
        onChangeText={v => update('bufferSizeKb', parseInt(v) || 256)}
        keyboardType="numeric"
        placeholderTextColor="#666"
      />

      <FieldLabel label="Pool Size" />
      <TextInput
        style={styles.input}
        value={String(draft.poolSize)}
        onChangeText={v => update('poolSize', parseInt(v) || 4)}
        keyboardType="numeric"
        placeholderTextColor="#666"
      />

      <Text style={styles.sectionTitle}>Cloudflare Proxy</Text>

      <SwitchRow
        label="CF Proxy Enabled"
        value={draft.cfProxy}
        onValueChange={v => update('cfProxy', v)}
      />
      <SwitchRow
        label="CF Proxy Priority"
        value={draft.cfProxyPriority}
        onValueChange={v => update('cfProxyPriority', v)}
      />

      <FieldLabel label="CF Proxy User Domain" />
      <TextInput
        style={styles.input}
        value={draft.cfProxyUserDomain}
        onChangeText={v => update('cfProxyUserDomain', v)}
        placeholder="Leave empty for auto"
        placeholderTextColor="#666"
        autoCapitalize="none"
      />

      <Text style={styles.sectionTitle}>Anti-Censorship</Text>

      <SwitchRow
        label="DPI Bypass (TCP Fragmentation)"
        value={draft.dpiBypass}
        onValueChange={v => update('dpiBypass', v)}
      />
      <Text style={styles.helperText}>
        Helps to bypass DPI blocks on cellular networks by splitting WS handshake packets.
      </Text>

      <TouchableOpacity style={styles.saveButton} onPress={handleSave}>
        <Text style={styles.saveText}>Save Settings</Text>
      </TouchableOpacity>
    </ScrollView>
  );
}

function FieldLabel({label}: {label: string}) {
  return <Text style={styles.fieldLabel}>{label}</Text>;
}

function SwitchRow({
  label,
  value,
  onValueChange,
}: {
  label: string;
  value: boolean;
  onValueChange: (v: boolean) => void;
}) {
  return (
    <View style={styles.switchRow}>
      <Text style={styles.switchLabel}>{label}</Text>
      <Switch
        value={value}
        onValueChange={onValueChange}
        trackColor={{false: '#444', true: '#0088CC'}}
        thumbColor="#FFF"
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: '#121212'},
  content: {padding: 20, paddingBottom: 40},
  sectionTitle: {
    color: '#0088CC',
    fontSize: 16,
    fontWeight: '700',
    marginTop: 20,
    marginBottom: 12,
  },
  fieldLabel: {color: '#999', fontSize: 13, marginBottom: 4, marginTop: 8},
  input: {
    backgroundColor: '#1E1E1E',
    color: '#FFF',
    borderRadius: 8,
    padding: 12,
    fontSize: 15,
    borderWidth: 1,
    borderColor: '#333',
  },
  secretRow: {flexDirection: 'row', gap: 8},
  regenButton: {
    backgroundColor: '#0088CC',
    borderRadius: 8,
    paddingHorizontal: 16,
    justifyContent: 'center',
  },
  regenText: {color: '#FFF', fontWeight: '600'},
  switchRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: '#2A2A2A',
  },
  switchLabel: {color: '#FFF', fontSize: 15},
  saveButton: {
    backgroundColor: '#0088CC',
    padding: 16,
    borderRadius: 12,
    alignItems: 'center',
    marginTop: 30,
  },
  saveText: {color: '#FFF', fontSize: 16, fontWeight: '700'},
  helperText: {
    fontSize: 12,
    color: '#888',
    marginBottom: 15,
    marginTop: -10,
    paddingHorizontal: 4,
  }
});
