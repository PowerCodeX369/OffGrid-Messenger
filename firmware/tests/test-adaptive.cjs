const assert=require('node:assert/strict');
const fs=require('node:fs');
const C=require('../web/adaptive.js');
function a(c,kind='image',rssi=-30,profile='auto',enabled=true){return c.window({kind,rssi,profile,enabled});}
const ctl=new C.PhotoFlowController();
assert.equal(a(ctl),1,'start safe');
for(let i=0;i<9;i++)ctl.success(250,1);
assert.equal(a(ctl),2,'promote to two on reliable strong signal');
for(let i=0;i<19;i++)ctl.success(230,1);
assert.equal(a(ctl),3,'promote to three after many clean ACKs');
assert.equal(a(ctl,'image',-80),1,'weak signal returns to one');
assert.equal(a(ctl,'image',null),1,'unknown signal safe');
assert.equal(a(ctl,'text'),1,'text ALWAYS one');
assert.equal(a(ctl,'delivered'),1,'receipt ALWAYS one');
assert.equal(a(ctl,'image',-20,'range'),1,'range first safe');
assert.equal(a(ctl,'image',-20,'auto',false),1,'manual rollback');
ctl.penalty('ACK timeout');assert.equal(a(ctl),1,'failure demotes immediately');
for(let i=0;i<8;i++)ctl.success(200,1);
assert.equal(a(ctl),1,'hysteresis: not immediately promoted');
for(let i=0;i<20;i++)ctl.success(200,1);
assert.ok(a(ctl)>=2,'eventually recover');
ctl.success(2700,1);assert.equal(a(ctl),1,'slow ack demotes');
const app=fs.readFileSync(require('node:path').join(__dirname,'../web/app.js'),'utf8');
assert(app.includes("chosenWindow(job)"),'scheduler must consult adaptive window');
assert(app.includes("job.kind==='image'&&!job.control"),'only photo ACKs train the controller');
assert(app.includes("kind:rssi")===false); // smoke test actual invocation is explicit
console.log('PASS adaptive photo ACK/RSSI controller, conservative fallback and text isolation');
