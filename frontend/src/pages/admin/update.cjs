const fs = require('fs');
const content = fs.readFileSync('Dashboard.tsx', 'utf8');
const newWidgets = fs.readFileSync('new_widgets.tsx', 'utf8');

const startIdx = content.indexOf('<div className="grid grid-cols-1 lg:grid-cols-2 gap-8">');
const endMarker = '</>\n        )}\n      </div>\n    </div>\n  );\n}';
const endIdx = content.indexOf(endMarker);

if (startIdx !== -1 && endIdx !== -1) {
  const newContent = content.substring(0, startIdx) + newWidgets + '\n          ' + endMarker;
  fs.writeFileSync('Dashboard.tsx', newContent);
  console.log('Replaced successfully');
} else {
  console.log('Markers not found');
}
