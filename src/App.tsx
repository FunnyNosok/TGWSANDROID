import React, {useState, useEffect} from 'react';
import {StatusBar} from 'react-native';
import {NavigationContainer} from '@react-navigation/native';
import {createNativeStackNavigator} from '@react-navigation/native-stack';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {SafeAreaProvider} from 'react-native-safe-area-context';

import HomeScreen from './screens/HomeScreen';
import SettingsScreen from './screens/SettingsScreen';
import LogsScreen from './screens/LogsScreen';
import FirstRunScreen from './screens/FirstRunScreen';
import {useSettings} from './hooks/useSettings';
import {FIRST_RUN_KEY} from './utils/constants';

const Stack = createNativeStackNavigator();

const screenOptions = {
  headerStyle: {backgroundColor: '#1E1E1E'},
  headerTintColor: '#FFFFFF',
  headerTitleStyle: {fontWeight: '600' as const},
  contentStyle: {backgroundColor: '#121212'},
};

function AppContent() {
  const {config, loaded} = useSettings();
  const [firstRun, setFirstRun] = useState<boolean | null>(null);

  useEffect(() => {
    (async () => {
      const done = await AsyncStorage.getItem(FIRST_RUN_KEY);
      setFirstRun(!done);
    })();
  }, []);

  if (!loaded || firstRun === null) return null;

  if (firstRun) {
    return (
      <FirstRunScreen
        config={config}
        onDone={async () => {
          await AsyncStorage.setItem(FIRST_RUN_KEY, 'done');
          setFirstRun(false);
        }}
      />
    );
  }

  return (
    <NavigationContainer>
      <Stack.Navigator screenOptions={screenOptions}>
        <Stack.Screen
          name="Home"
          component={HomeScreen}
          options={{title: 'TG WS Proxy'}}
        />
        <Stack.Screen
          name="Settings"
          component={SettingsScreen}
          options={{title: 'Settings'}}
        />
        <Stack.Screen
          name="Logs"
          component={LogsScreen}
          options={{title: 'Logs'}}
        />
      </Stack.Navigator>
    </NavigationContainer>
  );
}

export default function App() {
  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" backgroundColor="#121212" />
      <AppContent />
    </SafeAreaProvider>
  );
}
