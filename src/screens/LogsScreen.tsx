import React, {useState, useEffect, useRef} from 'react';
import {View, Text, FlatList, StyleSheet, TouchableOpacity} from 'react-native';
import {ProxyModule} from '../native/ProxyModule';

export default function LogsScreen() {
  const [logs, setLogs] = useState<string[]>([]);
  const flatListRef = useRef<FlatList>(null);

  useEffect(() => {
    const poll = async () => {
      try {
        const l = await ProxyModule.getLogs();
        setLogs(l || []);
      } catch {}
    };

    poll();
    const id = setInterval(poll, 2000);
    return () => clearInterval(id);
  }, []);

  const handleClear = () => setLogs([]);

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>Logs ({logs.length})</Text>
        <TouchableOpacity onPress={handleClear}>
          <Text style={styles.clearText}>Clear</Text>
        </TouchableOpacity>
      </View>
      <FlatList
        ref={flatListRef}
        data={logs}
        keyExtractor={(_, i) => String(i)}
        renderItem={({item}) => <Text style={styles.logLine}>{item}</Text>}
        onContentSizeChange={() =>
          flatListRef.current?.scrollToEnd({animated: false})
        }
        style={styles.list}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: '#121212'},
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 16,
    borderBottomWidth: 1,
    borderBottomColor: '#2A2A2A',
  },
  title: {color: '#FFF', fontSize: 16, fontWeight: '700'},
  clearText: {color: '#0088CC', fontSize: 14},
  list: {flex: 1, padding: 12},
  logLine: {
    color: '#CCC',
    fontSize: 11,
    fontFamily: 'monospace',
    lineHeight: 18,
    paddingVertical: 1,
  },
});
