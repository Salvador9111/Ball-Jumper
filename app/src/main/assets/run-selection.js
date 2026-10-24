(() => {
  const buttons = [...document.querySelectorAll('button')];
  buttons.forEach((button,index) => button.addEventListener('click', () => RunPage.choose(index)));
  const leaves=[...document.querySelectorAll('div,span')].filter(e=>e.childElementCount===0);
  const originals=new Map(leaves.map(e=>[e.textContent.trim(),e]));
  const bestLabels=leaves.filter(e=>e.textContent.trim()==='0');
  window.updateRun = data => {
    const change=(old,value)=>{ const e=originals.get(old); if(e)e.textContent=value; };
    change('Rookie',data.title); change('LEVEL 1','LEVEL '+data.level);
    change('1',String(data.level));
    change('0 / 1,000 EXP',data.exp);
    change('1,000 to lvl 2',data.next);
    bestLabels.forEach((e,i)=>{e.textContent=String(data.best[i]||0);e.style.fontSize= data.best[i]>=100000?'23px':'34px';});
    const segments=[...document.querySelectorAll('div')].filter(e=>e.style.height==='10px');
    segments.forEach((e,i)=>{e.style.background=i<data.progress*10?'#FF5B3A':'transparent';});
  };
  document.fonts.ready.then(()=>RunPage.ready());
})();
