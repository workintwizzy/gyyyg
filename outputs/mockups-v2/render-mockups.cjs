const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const assert = require('node:assert/strict');
let renderBrowser;

(async () => {
  const folder = __dirname;
  const browser = await chromium.launch({ executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true });
  renderBrowser=browser;
  const page = await browser.newPage({ viewport:{width:920,height:1000},deviceScaleFactor:2,reducedMotion:'reduce' });
  const errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  async function load() {
    await page.goto(pathToFileURL(path.join(folder,'preview.html')).href);
    const frame=page.frames().find(f=>f.parentFrame());
    await frame.waitForSelector('#trainingV2');
    await frame.evaluate(async()=>{lucide.createIcons();window.__trainingPreview.choose('home');await document.fonts.ready;await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)))});
    assert.equal(await frame.locator('i[data-lucide]').count(),0,'Standalone preview must render every icon offline');
    return frame;
  }
  let frame=await load();
  assert.equal(await frame.evaluate(()=>document.fonts.check('500 16px GolosPreview')),true);
  await frame.evaluate(()=>document.getElementById('trainingV2').style.setProperty('--vp-body-weight','600'));
  assert.equal(await frame.evaluate(()=>getComputedStyle(document.querySelector('.vp-ai-answer')).fontWeight),'600');
  await frame.evaluate(()=>document.getElementById('trainingV2').style.setProperty('--vp-body-weight','500'));
  for(const [view,name] of [['home','01-главная.png'],['journal','02-журнал.png'],['active','03-подход.png'],['coach','04-тренер.png']]) {
    await frame.evaluate(view=>window.__trainingPreview.choose(view),view);
    await frame.locator('[data-phone="'+view+'"]').screenshot({path:path.join(folder,name),animations:'disabled'});
  }
  await frame.evaluate(()=>{
    window.__trainingPreview.freeze();
    const root=document.getElementById('trainingV2');
    root.querySelectorAll('.vp-view').forEach(e=>e.hidden=false);
    root.querySelector('.vp-selector').hidden=true;
    root.querySelector('.vp-demo').hidden=true;
    const stage=root.querySelector('.vp-stages');
    Object.assign(stage.style,{gridTemplateColumns:'390px 390px',gap:'28px',justifyContent:'center',padding:'24px',background:'#edf0ed'});
    document.documentElement.style.setProperty('--foreground','#263238');
    document.querySelectorAll('.vp-canvas-label').forEach(e=>Object.assign(e.style,{fontSize:'17px',fontWeight:'600'}));
  });
  await page.setViewportSize({width:920,height:1900});
  await frame.locator('.vp-stages').screenshot({path:path.join(folder,'четыре-экрана.png'),animations:'disabled'});

  frame=await load();
  await frame.locator('[data-phone=home] [data-go=active]').click();
  assert.equal(await frame.evaluate(()=>__trainingPreview.state.view),'active');
  await frame.locator('[data-step="weight:1"]').click();
  assert.equal(await frame.locator('#tv-weight').inputValue(),'42,5');
  await frame.locator('#tv-save-set').click();
  assert.equal(await frame.evaluate(()=>__trainingPreview.exercises[0].done.length),3);
  assert.equal(await frame.locator('#tv-save-set').isDisabled(),true);
  await frame.evaluate(()=>__trainingPreview.choose('coach'));
  assert.match(await frame.locator('#tv-proposal-host').innerText(),/Журнал изменился/);
  await frame.locator('[data-action=refresh-proposal]').click();
  await frame.locator('[data-action=apply]').click();
  assert.equal(await frame.evaluate(()=>__trainingPreview.exercises.reduce((n,e)=>n+e.sets,0)),14);
  assert.equal(await frame.evaluate(()=>__trainingPreview.exercises[0].done.length),3);
  assert.match(await frame.locator('[data-count=home]').innerText(),/3 из 14/);

  frame=await load();
  await frame.evaluate(()=>__trainingPreview.choose('coach'));
  await frame.locator('[data-action=reject]').click();
  assert.equal(await frame.evaluate(()=>__trainingPreview.exercises.reduce((n,e)=>n+e.sets,0)),17);
  await frame.evaluate(()=>__trainingPreview.choose('home'));
  await frame.locator('[data-action=adjust]').click();
  await frame.locator('[data-minutes="30"]').click();
  await frame.locator('[data-action=ask-adjust]').click();
  assert.equal(await frame.evaluate(()=>__trainingPreview.state.view),'coach');

  const layout=[];
  for(const width of [360,412]) {
    await page.setViewportSize({width,height:1000});
    for(const view of ['home','journal','active','coach']) {
      await frame.evaluate(view=>__trainingPreview.choose(view),view);
      const metrics=await frame.evaluate(view=>{
        const phone=document.querySelector('[data-phone="'+view+'"]');
        const rect=phone.getBoundingClientRect();
        return {view,width:rect.width,overflow:phone.scrollWidth>phone.clientWidth+1,font:getComputedStyle(phone).fontFamily,weight:getComputedStyle(phone).fontWeight};
      },view);
      assert.equal(metrics.overflow,false,JSON.stringify(metrics));layout.push({viewport:width,...metrics});
    }
  }
  assert.deepEqual(errors,[]);
  fs.writeFileSync(path.join(folder,'preview-checks.json'),JSON.stringify({runtimeErrors:errors,interactions:'passed',layout},null,2));
  await browser.close();
  console.log('Four screenshots, overview, local interactions and 360/412 px layouts verified.');
})().catch(async e=>{console.error(e);await renderBrowser?.close();process.exitCode=1});
