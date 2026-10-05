const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const checker = path.join(__dirname, 'check-map-geometry.cjs');
function check(nodes, segments) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'amhs-geometry-'));
  try {
    const file = path.join(dir, 'map.json');
    fs.writeFileSync(file, JSON.stringify({nodes, segments}));
    return spawnSync(process.execPath, [checker, file, '100'], {encoding:'utf8'});
  } finally { fs.rmSync(dir, {recursive:true, force:true}); }
}
const node = (code,xMm,yMm) => ({code,xMm,yMm});
const pair = (from,to) => [{from,to},{from:to,to:from}];
const conflicts = result => Number(result.stdout.match(/Segments that pass within[^\n]+: (\d+)/)?.[1]);
test('detects central intersections even when every endpoint is far away', () => {
  const result = check([node('A',-50000,0),node('B',50000,0),node('C',0,-50000),node('D',0,50000)],
    [...pair('A','B'),...pair('C','D')]);
  assert.equal(result.status,1,result.stderr);
  assert.equal(conflicts(result),4,result.stdout);
});
test('does not waive an actual crossing just because a neighbouring junction is nearby', () => {
  const result = check([node('A',-3000,0),node('B',3000,0),node('C',0,-3000),node('D',0,3000)],
    [...pair('A','B'),...pair('C','D'),...pair('A','C')]);
  assert.equal(result.status,1,result.stderr);
  assert.equal(conflicts(result),4,result.stdout);
});
test('separated parallel loops have no crossing', () => {
  const result = check([node('A',0,0),node('B',50000,0),node('C',0,5000),node('D',50000,5000)],
    [...pair('A','B'),...pair('C','D')]);
  assert.equal(result.status,0,result.stdout+result.stderr);
  assert.equal(conflicts(result),0);
});
