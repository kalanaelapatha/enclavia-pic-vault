/**
 * @format
 */

import { AppRegistry } from 'react-native';
import { installNetworkGuard } from './src/services/networkGuard';
import App from './App';
import { name as appName } from './app.json';

if (!__DEV__) {
  installNetworkGuard();
}

AppRegistry.registerComponent(appName, () => App);
