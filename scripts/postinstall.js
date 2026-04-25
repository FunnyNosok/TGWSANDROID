const fs = require('fs');
const path = require('path');

const pluginDir = path.join(__dirname, '..', 'node_modules', '@react-native', 'gradle-plugin');

function patchFile(filePath) {
  if (!fs.existsSync(filePath)) return;
  let content = fs.readFileSync(filePath, 'utf8');
  if (content.includes('jvmToolchain(17)')) {
    content = content.replace(/jvmToolchain\(17\)/g, 'jvmToolchain(21)');
    fs.writeFileSync(filePath, content, 'utf8');
    console.log(`Patched: ${filePath}`);
  }
}

const filesToPatch = [
  'settings-plugin/build.gradle.kts',
  'shared/build.gradle.kts',
  'shared-testutil/build.gradle.kts',
  'react-native-gradle-plugin/build.gradle.kts',
  'react-native-gradle-plugin/src/main/kotlin/com/facebook/react/utils/JdkConfiguratorUtils.kt',
];

for (const file of filesToPatch) {
  patchFile(path.join(pluginDir, file));
}

console.log('JDK 21 toolchain patch complete.');
