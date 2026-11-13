const fs = require('node:fs');
const path = require('node:path');

// The in-chat fragment uses the host's icon library. The portable preview
// embeds the same licensed library so its controls also render offline.
const library = path.dirname(require.resolve('lucide/package.json'));
const icons = fs.readFileSync(path.join(library, 'dist/umd/lucide.js'), 'utf8');
const fragment = fs.readFileSync(path.join(__dirname, 'training-v2.html'), 'utf8');
fs.writeFileSync(path.join(__dirname, 'preview-source.html'), '<script>' + icons + '</script>\n' + fragment);
fs.copyFileSync(path.join(library, 'LICENSE'), path.join(__dirname, 'assets', 'LUCIDE-LICENSE.txt'));
