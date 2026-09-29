const {createRequire}=require('node:module');
const requireRuntime=createRequire(process.env.AMHS_RUNTIME_MODULES+'/package.json');
const {chromium}=requireRuntime('playwright');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});
 try {
 const page=await browser.newPage({viewport:{width:1440,height:1050}});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('http://127.0.0.1:5173');
 await page.getByRole('heading',{name:'가상 FAB 지도',exact:true}).waitFor();
 assert.equal(await page.locator('svg > g > circle').count(),6);
 await page.getByRole('button',{name:'OHT-01 OHT / 미연결',exact:true}).click();
 await page.locator('.detail').getByRole('heading',{name:'OHT-01',exact:true}).waitFor();
 assert.match(await page.locator('.detail').innerText(),/OHT-N1/);
 await page.screenshot({path:'docs/amhs-running.png',fullPage:true});
 const response=await page.request.get('http://127.0.0.1:5173/api/v1/sites/999/maps/1');assert.equal(response.status(),404);
 const map=await (await page.request.get('http://127.0.0.1:5173/api/v1/sites/1/maps/1')).json();
 assert.equal(map.nodes.length,6);assert.equal(map.edges.length,8);assert.equal(map.ports.length,4);
 await page.setViewportSize({width:390,height:844});
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
 await page.screenshot({path:'docs/amhs-mobile.png',fullPage:true});
 await page.route('**/api/v1/sites/1/maps/1',route=>route.fulfill({status:503,body:'unavailable'}));
 await page.getByRole('button',{name:'새로고침'}).click();
 await page.getByRole('alert').waitFor();
 assert.equal(await page.locator('svg').count(),0);
 assert.deepEqual(errors,[]);
 console.log('PASS: real DB map / vehicle selection / wrong-site 404 / mobile overflow / fetch failure / no JS errors');
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exit(1)});
