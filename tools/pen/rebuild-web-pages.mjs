import { spawnSync } from "node:child_process";
import { copyFileSync, mkdirSync, readFileSync, readdirSync, rmSync } from "node:fs";
import path from "node:path";

const pages = {
  "cash-import": "导入账单",
  "cash-categories": "分类管理",
  "investment-holdings": "当前持仓",
  "investment-events": "投资事件",
  "workspace-management": "工作区管理",
  login: "登录到你的账本",
  "workspace-entry": "创建工作区",
  invitation: "加入共享账本",
  "cash-record": "新建流水",
};

const selected = process.argv.slice(2);
const targets = selected.length ? selected : Object.keys(pages);
const tableComponents = Object.fromEntries(JSON.parse(readFileSync("design/finance-ui.lib.pen", "utf8")).children
  .filter((node) => ["Finance/DataTable/InvestmentHoldings", "Finance/DataTable/InvestmentEvents"].includes(node.name))
  .map((node) => [node.name, node.id]));
if (Object.keys(tableComponents).length !== 2) throw new Error("请先运行 node tools/pen/build-finance-tables.mjs");
for (const key of targets) {
  if (!pages[key]) throw new Error(`未知页面：${key}`);
  for (const suffix of [320, 375, 390, 414, 768, 1440, "states"]) {
    rmSync(`/tmp/finance-pen-${key}-${suffix}`, { recursive: true, force: true });
  }
  const code = String.raw`
const key = ${JSON.stringify(key)};
const pageTitle = ${JSON.stringify(pages[key])};
const tableComponents = ${JSON.stringify(tableComponents)};
for (const id of ["FyRXo","aBXaZ","ng4GN","CCS6d","Uol99"]) Delete(id);
const ink="#202124", muted="#6f7279", pale="#f5f5f5", line="#e4e4e7", white="#ffffff", accent="#0485f7", green="#14865a", red="#c8372b";
function frame(parent,name,props={}) { return Insert(parent,{type:"frame",name,layout:"vertical",width:"fill_container",height:"fit_content",...props}); }
function row(parent,name,props={}) { return Insert(parent,{type:"frame",name,layout:"horizontal",width:"fill_container",height:"fit_content",alignItems:"center",...props}); }
function text(parent,name,content,size=14,color=ink,props={}) { return Insert(parent,{type:"text",name,content,fontFamily:"Roboto",fontSize:size,fill:color,textGrowth:"fixed-width",width:"fill_container",...props}); }
function card(parent,name,props={}) { return frame(parent,name,{fill:white,cornerRadius:8,padding:20,gap:14,...props}); }
function button(parent,name,kind="primary",props={}) {
  const primary=kind==="primary", danger=kind==="danger";
  return Insert(parent,{type:"ref",name,ref:danger?"D:Yw0Z4":primary?"D:CjC40":"D:uEyLt",height:36,...props,descendants:{[danger?"D:BN8Aq":primary?"D:IWqn3":"D:Wee4B"]:{content:name}}});
}
function field(parent,label,value="",kind="input",props={}) {
  const group=frame(parent,label,{gap:6,...props});
  text(group,label+"标签",label,12,muted);
  if(kind==="select") Insert(group,{type:"ref",name:label+"选择",ref:"D:ydzXW",width:"fill_container",height:36,descendants:{"D:qt1Ap":{content:value||"请选择"}}});
  else Insert(group,{type:"ref",name:label+"输入",ref:"D:Q2EsN",width:"fill_container",height:40,descendants:{"D:lkemR":{enabled:false},"D:YDsht":{content:value||"请输入"}}});
  return group;
}
function divider(parent,name) { Insert(parent,{type:"rectangle",name,width:"fill_container",height:1,fill:line}); }
function shell(w,h) {
  const compact=w<=414, regular=w===768;
  const screen=Insert(document,{type:"frame",name:pageTitle+" / "+(compact?"Compact":regular?"Regular":"Wide")+" / "+w,x:w===320?0:w===375?390:w===390?835:w===414?1295:w===768?1779:2617,y:0,width:w,height:h,layout:compact||regular?"vertical":"horizontal",fill:pale,clip:true,placeholder:true});
  if(compact||regular){
    const top=row(screen,"移动顶栏",{height:64,padding:[0,18],fill:"#18181b",justifyContent:"space_between"});
    Insert(top,{type:"icon",name:"打开菜单",library:"lucide",icon:"menu",width:22,height:22,fill:white});
    text(top,"产品名","Finance Tracker",17,white,{width:regular?220:230,textAlign:"center",fontWeight:"600"});
    text(top,"账户","0",14,white,{width:24,textAlign:"center"});
  } else {
    const nav=frame(screen,"侧边导航",{width:220,height:"fill_container",fill:"#18181b",padding:[28,18],gap:26});
    text(nav,"品牌","Finance Tracker",18,white,{fontWeight:"600"});
    for(const label of ["收支账本","分类管理","导入账单","投资账本","当前持仓","投资事件","工作区管理"]){
      if(label==="投资账本"){
        text(nav,"投资导航分组",label,13,white,{fontWeight:"600"});
        continue;
      }
      const item=row(nav,label,{height:38,padding:[0,12],cornerRadius:6,fill:label===pageTitle?"#303034":"#18181b"});
      text(item,label+"导航",label,14,label===pageTitle?white:"#b2b2b8");
    }
    const account=frame(nav,"当前账户",{height:"fill_container",justifyContent:"end",gap:8});
    divider(account,"账户分隔线");
    text(account,"账户邮箱","owner@example.com",12,"#b2b2b8");
    text(account,"当前工作区","家庭账本 · 管理员",12,white);
  }
  const main=frame(screen,"页面内容",{width:compact?w:regular?w:w-220,height:"fill_container",padding:compact?[22,16]:regular?[28,28]:[36,42],gap:22});
  text(main,"页面标题",pageTitle,compact?28:32,ink,{fontWeight:"700"});
  divider(main,"页头分隔线");
  return {screen,main,compact,regular,w};
}
function accessShell(w,h){
  const compact=w<=414, entry=key==="workspace-entry";
  const screen=Insert(document,{type:"frame",name:pageTitle+" / "+w,x:w===320?0:w===375?390:w===390?835:w===414?1295:w===768?1779:2617,y:0,width:w,height:h,layout:"vertical",fill:pale,alignItems:"center",justifyContent:entry?"start":"center",padding:entry?[compact?28:90,compact?16:32]:[20,16],clip:true,placeholder:true});
  const main=frame(screen,"访问内容",{width:entry?(compact?w-32:Math.min(w-64,720)):(compact?w-32:430),padding:entry?0:24,gap:16,fill:entry?pale:white,cornerRadius:entry?0:8});
  return {screen,main,compact,regular:w===768,w};
}
function recordShell(w,h){
  const compact=w<=414;
  const screen=Insert(document,{type:"frame",name:"收支流水 / "+w,x:w===320?0:w===375?390:w===390?835:w===414?1295:w===768?1779:2617,y:0,width:w,height:h,layout:"horizontal",fill:"#d9d9dc",justifyContent:"end",clip:true,placeholder:true});
  if(w>768){
    const under=row(screen,"账本背景",{width:w-480,height:"fill_container",alignItems:"start",fill:"#a0a2a5"});
    const sidebar=frame(under,"账本侧边栏",{width:220,height:"fill_container",fill:"#202630",padding:[28,18],gap:24});
    text(sidebar,"品牌","Finance Tracker",17,"#b6bdc4",{fontWeight:"600"});
    for(const label of ["收支账本","分类管理","导入账单","投资账本","当前持仓","投资事件","工作区管理"])
      text(sidebar,label,label,13,"#919aa4");
    const ledger=frame(under,"账本内容",{width:w-700,height:"fill_container",padding:28,gap:18,fill:"#a6aaad"});
    text(ledger,"背景标题","收支账本",28,"#34383e",{fontWeight:"700"});
    const bar=card(ledger,"背景筛选",{height:72,fill:"#c8cbce"});
    text(bar,"背景筛选标题","筛选 · 全部账户 · 全部收支",14,"#646a70");
    const list=card(ledger,"背景记录",{padding:0,gap:0,fill:"#c8cbce"});
    const head=row(list,"记录表头",{height:38,padding:[0,16],fill:"#343b43"});
    text(head,"列名","发生时间                 账户                 交易信息",11,"#c2c7cc");
    const body=row(list,"记录行",{height:60,padding:[0,16]});
    text(body,"记录摘要","2026 年 7 月 3 日         日常账户         第一笔",12,"#6b7178");
  }
  const main=frame(screen,"记账抽屉",{width:compact?w:w===768?w:480,height:"fill_container",padding:compact?[16,16]:[28,26],gap:8,fill:white});
  return {screen,main,compact,regular:w===768,w};
}
function loginPage(s){
  const m=s.main;
  text(m,"入口标记","工作区访问",13,accent,{fontWeight:"600"});
  text(m,"登录标题","登录到你的账本",s.compact?27:30,ink,{fontWeight:"700"});
  field(m,"邮箱","name@example.com");
  field(m,"密码","输入密码");
  button(m,"登录","primary",{width:"fill_container"});
  text(m,"注册入口","还没有账户？注册",13,accent,{textAlign:"center"});
}
function workspaceEntryPage(s){
  const m=s.main;
  text(m,"入口标记","新工作区",13,accent,{fontWeight:"600"});
  text(m,"创建标题","创建工作区",s.compact?28:32,ink,{fontWeight:"700"});
  text(m,"创建说明","创建后你将成为首位管理员，可以再邀请其他成员。",14,muted);
  const form=card(m,"创建表单",{gap:14});
  field(form,"工作区名称","输入工作区名称");
  button(form,"创建工作区","primary",{width:"fill_container"});
}
function invitationPage(s){
  const m=s.main;
  text(m,"入口标记","工作区邀请",13,accent,{fontWeight:"600"});
  text(m,"邀请标题","加入共享账本",s.compact?28:30,ink,{fontWeight:"700"});
  text(m,"权限说明","管理员已在创建邀请时确定你的权限，接受后不能自行更改。",14,muted);
  const role=frame(m,"可编辑权限",{padding:14,gap:6,fill:pale,cornerRadius:6});
  text(role,"角色","可编辑",15,ink,{fontWeight:"600"});
  text(role,"角色范围","可查看和修改账本、导入和关联关系。",13,muted);
  button(m,"接受邀请","primary",{width:120});
  text(m,"取消加入","暂不加入",13,accent,{textAlign:"center"});
}
function cashRecordPage(s){
  const m=s.main;
  const heading=row(m,"抽屉标题",{justifyContent:"space_between"});
  const titles=frame(heading,"标题区",{gap:2});
  text(titles,"来源","收支账本",11,muted);
  text(titles,"标题","新建流水",20,ink,{fontWeight:"700"});
  Insert(heading,{type:"icon",name:"关闭",library:"lucide",icon:"x",width:20,height:20,fill:muted});
  divider(m,"表单分隔线");
  const amount=row(m,"金额摘要",{gap:8,height:62,alignItems:"center"});
  Insert(amount,{type:"ref",name:"金额输入",ref:"D:Q2EsN",width:"fill_container",height:48,descendants:{"D:lkemR":{enabled:false},"D:YDsht":{content:"0"}}});
  Insert(amount,{type:"ref",name:"币种选择",ref:"D:ydzXW",width:92,height:48,descendants:{"D:qt1Ap":{content:"CNY"}}});
  divider(m,"金额分隔线");
  for(const [label,value,kind] of [["交易对方","","input"],["对方账号","","input"],["发生时间","选择日期和时间","input"],["账户","日常账户","select"],["流水类型","消费","select"],["分类","无分类","select"],["备注","","input"]]){
    const r=row(m,label+"行",{height:label==="备注"?72:52,gap:8});
    text(r,label+"标签",label,13,muted,{width:108});
    if(kind==="select") Insert(r,{type:"ref",name:label+"选择",ref:"D:ydzXW",width:"fill_container",height:40,descendants:{"D:qt1Ap":{content:value}}});
    else Insert(r,{type:"ref",name:label+"输入",ref:"D:Q2EsN",width:"fill_container",height:label==="备注"?66:40,descendants:{"D:lkemR":{enabled:false},"D:YDsht":{content:value||" "}}});
    divider(m,label+"分隔线");
  }
  const actions=row(m,"记账操作",{gap:10,justifyContent:"end"});
  button(actions,"保存","primary",{width:76});
}
function importPage(s){
  const m=s.main;
  const steps=row(m,"导入步骤",{gap:s.compact?4:12,height:44});
  ["选择文件","映射账户","核对流水","配对"].forEach((v,i)=>{
    const step=frame(steps,"步骤 "+v,{width:"fill_container",height:42,gap:4});
    text(step,"步骤名",(i+1)+"  "+v,s.compact?11:13,i===0?accent:muted,{fontWeight:i===0?"600":"400"});
    Insert(step,{type:"rectangle",name:"步骤指示",width:"fill_container",height:2,fill:i===0?accent:line});
  });
  const panel=card(m,"选择文件",{gap:20,padding:s.compact?18:28});
  text(panel,"区域标题","选择文件",19,ink,{fontWeight:"600"});
  const drop=frame(panel,"上传区域",{height:s.compact?200:230,fill:"#fafafa",stroke:"#c8c8cf",strokeWidth:1,cornerRadius:8,justifyContent:"center",alignItems:"center",gap:12});
  Insert(drop,{type:"icon",name:"上传图标",library:"lucide",icon:"arrow-up",width:32,height:32,fill:accent});
  text(drop,"上传指令","拖入账单文件",16,ink,{width:"fill_container",textAlign:"center",fontWeight:"600"});
  text(drop,"格式","CSV、XLS、XLSX、PDF",12,muted,{textAlign:"center"});
  button(drop,"选择文件","secondary",{width:100});
  divider(panel,"操作分隔线");
  const actions=row(panel,"操作",{gap:10,justifyContent:"space_between"});
  button(actions,"取消","secondary",{width:s.compact?"fill_container":90});
  button(actions,"下一步","primary",{width:s.compact?"fill_container":100,opacity:0.45});
}
function categoriesPage(s){
  const m=s.main;
  const layout=row(m,"分类工作台",{gap:18,alignItems:"start"});
  if(s.compact||s.regular) Update(layout,{layout:"vertical"});
  const directory=card(layout,"分类目录",{width:s.compact||s.regular?"fill_container":680,padding:0,gap:0});
  const head=frame(directory,"目录标题和搜索",{padding:16,gap:10,layout:s.compact||s.regular?"vertical":"horizontal",alignItems:s.compact||s.regular?"start":"center"});
  text(head,"目录标题","分类目录",17,ink,{fontWeight:"600"});
  field(head,"搜索分类","搜索分类","input",{width:s.compact||s.regular?"fill_container":280});
  divider(directory,"列表分隔线");
  for(const [name,sub] of [["餐饮",""],["咖啡","餐饮"]]){
    const item=row(directory,"分类 "+name,{height:60,padding:[0,16],gap:12});
    const copy=frame(item,"分类文字",{gap:4});
    text(copy,"分类名",name,14,ink,{fontWeight:"600"});
    if(sub) text(copy,"分类路径",sub,11,muted);
    const actions=row(item,"分类操作",{width:sub?92:58,gap:12,justifyContent:"end"});
    for(const icon of sub?["plus","pencil","arrow-up"]:["plus","pencil"])
      Insert(actions,{type:"icon",name:icon,library:"lucide",icon,width:16,height:16,fill:accent});
    divider(directory,"分类分隔线");
  }
  const create=row(directory,"新建一级分类",{height:56,padding:[0,16]});
  text(create,"新建一级分类文案","＋  新建一级分类",14,accent);
  const editor=card(layout,"分类编辑",{width:"fill_container",height:s.compact?180:240,gap:14,justifyContent:"center",alignItems:"center"});
  text(editor,"未选分类","选择一个分类。",15,muted,{textAlign:"center"});
}
function holdingsPage(s){
  const m=s.main;
  const heading=row(m,"持仓操作",{justifyContent:"space_between"});
  text(heading,"列表标题","当前持仓",20,ink,{fontWeight:"600"});
  button(heading,"刷新","secondary",{width:76});
  const filters=card(m,"显示与筛选",{gap:12});
  const filterHeading=row(filters,"筛选标题行",{justifyContent:"space_between"});
  text(filterHeading,"筛选标题","显示",16,ink,{fontWeight:"600"});
  text(filterHeading,"收起筛选","收起",12,accent,{width:42,textAlign:"right"});
  const grid=row(filters,"筛选字段",{gap:10,alignItems:"start"});
  if(s.compact||s.regular) Update(grid,{layout:"vertical"});
  for(const [label,value,kind] of [["账户","全部账户","select"],["排序","市值倒序","select"],["同一标的","分开显示","select"],["币种","原币种","input"],["时间范围","近 24 小时","select"]]) field(grid,label,value,kind,{width:"fill_container"});
  const metrics=row(m,"持仓摘要",{gap:10,alignItems:"start"});
  if(s.compact||s.regular) Update(metrics,{layout:"vertical"});
  for(const [label,value,detail] of [["总浮盈亏","+20 USD","+10%"],["近 24 小时浮盈亏","+8 USD","+4%"],["当前总市值","220 USD",""]]){
    const metric=card(metrics,label,{width:"fill_container",gap:6,padding:16});
    text(metric,label+"标签",label,12,muted);
    text(metric,label+"金额",value,20,label==="当前总市值"?ink:green,{fontWeight:"600"});
    if(detail) text(metric,label+"比例",detail,12,muted);
  }
  if(!s.compact&&!s.regular){
    Insert(m,{type:"ref",name:"持仓表格",ref:"D:"+tableComponents["Finance/DataTable/InvestmentHoldings"],width:"fill_container"});
    return;
  }
  const position=card(m,"Apple 持仓",{gap:8,padding:18});
  const first=row(position,"标的和现价",{justifyContent:"space_between"});
  text(first,"标的","Apple",17,ink,{fontWeight:"600"});
  text(first,"现价","110 USD",15,red,{width:120,textAlign:"right"});
  text(position,"标的账户","AAPL · 证券账户",12,muted);
  text(position,"价格详情","现价 110 USD · 平均成本 100 USD · 报价于 148h 13m 前",12,muted);
  divider(position,"持仓分隔线");
  for(const [label,value] of [["数量","2"],["当前市值","220 USD"],["仓位","+100%"],["浮盈亏","+20 USD"],["浮盈亏率","+10%"],["近 24 小时盈亏","+8 USD"],["近 24 小时盈亏率","+4%"]]){
    const r=row(position,label,{justifyContent:"space_between"});
    text(r,label+"标签",label,13,muted);
    text(r,label+"值",value,14,label==="浮盈亏"?green:ink,{width:120,textAlign:"right"});
  }
}
function eventsPage(s){
  const m=s.main;
  const filters=card(m,"投资事件筛选",{gap:12});
  const filterHeading=row(filters,"筛选摘要",{justifyContent:"space_between"});
  text(filterHeading,"筛选标题","筛选",16,ink,{fontWeight:"600"});
  Insert(filterHeading,{type:"icon",name:"收起筛选",library:"lucide",icon:"chevron-up",width:18,height:18,fill:accent});
  text(filters,"当前筛选","全部账户 · 全部事件",12,muted);
  divider(filters,"筛选分隔线");
  const fields=row(filters,"筛选字段",{gap:10,alignItems:"start"});
  if(s.compact||s.regular) Update(fields,{layout:"vertical"});
  for(const [label,value,kind] of [["开始日期","选择日期","input"],["结束日期","选择日期","input"],["投资账户","全部账户","select"],["事件类型","全部事件类型","select"],["标的","如 AAPL 或 .US","input"]]) field(fields,label,value,kind,{width:"fill_container"});
  const heading=row(m,"事件列表标题",{justifyContent:"space_between"});
  text(heading,"列表标题","投资事件",20,ink,{fontWeight:"600"});
  text(heading,"数量",s.compact?"已加载 1 条":"已加载 2 条",12,muted,{width:100,textAlign:"right"});
  if(!s.compact&&!s.regular){
    Insert(m,{type:"ref",name:"投资事件表格",ref:"D:"+tableComponents["Finance/DataTable/InvestmentEvents"],width:"fill_container"});
    text(m,"分页反馈","已显示全部记录。",13,muted,{textAlign:"center"});
    return;
  }
  for(let i=0;i<(s.compact?1:2);i++){
    const event=card(m,"买入苹果 "+(i+1),{gap:10,padding:16});
    const top=row(event,"时间和类型",{justifyContent:"space_between"});
    text(top,"发生时间","2026 年 9 月 24 日 10:30",13,muted);
    text(top,"事件类型","买入",14,accent,{width:50,textAlign:"right",fontWeight:"600"});
    text(event,"账户","证券账户",13,muted);
    divider(event,"资产分隔线");
    const amounts=row(event,"资产变化",{justifyContent:"space_between"});
    text(amounts,"流出","流出  -220 USD",14,red);
    text(amounts,"流入","流入  +2 AAPL",14,green,{width:150,textAlign:"right"});
    text(event,"手续费与备注",i===0?"手续费 1 USD · 买入苹果":"手续费 1 USD · 第二条投资事件",12,muted);
  }
  button(m,"加载更多","secondary",{width:s.compact?"fill_container":130});
}
function workspacePage(s){
  const m=s.main;
  const identity=card(m,"工作区信息",{gap:14});
  text(identity,"信息标题","工作区信息",18,ink,{fontWeight:"600"});
  const details=row(identity,"工作区资料",{gap:24,alignItems:"start"});
  if(s.compact||s.regular) Update(details,{layout:"vertical"});
  const nameGroup=frame(details,"名称资料",{width:"fill_container",gap:6});
  text(nameGroup,"名称标签","工作区名称",12,muted);
  const name=row(nameGroup,"名称编辑",{gap:10});
  Insert(name,{type:"ref",name:"工作区名称",ref:"D:Q2EsN",width:"fill_container",height:40,descendants:{"D:lkemR":{enabled:false},"D:YDsht":{content:"家庭账本"}}});
  button(name,"保存","primary",{width:80,opacity:0.5});
  const idGroup=frame(details,"固定 ID 资料",{width:"fill_container",gap:6});
  text(idGroup,"ID 标签","固定 ID",12,muted);
  const id=row(idGroup,"固定 ID",{justifyContent:"space_between"});
  text(id,"ID","workspace-1",13,ink);
  button(id,"复制","secondary",{width:72});
  const members=card(m,"成员",{gap:12});
  const mh=row(members,"成员标题",{justifyContent:"space_between"});
  text(mh,"成员标题文字","成员",18,ink,{fontWeight:"600"});
  text(mh,"成员数量","2 位成员",12,muted,{width:80,textAlign:"right"});
  divider(members,"成员分隔线");
  for(const [email,role] of [["owner@example.com","管理员"],["member@example.com","可编辑"]]){
    const member=row(members,email,{justifyContent:"space_between",gap:12});
    const identity=frame(member,"成员身份",{gap:3});
    if(role==="管理员") text(identity,"本人","你",13,ink,{fontWeight:"600"});
    text(identity,"邮箱",email,13,role==="管理员"?muted:ink);
    if(role==="管理员") text(member,"权限",role,13,muted,{width:72,textAlign:"right"});
    else {
      Insert(member,{type:"ref",name:"成员权限",ref:"D:ydzXW",width:s.compact?108:140,height:40,descendants:{"D:qt1Ap":{content:"可编辑"}}});
      if(!s.compact) button(member,"移除","secondary",{width:70});
      else button(members,"移除","secondary",{width:70});
    }
    divider(members,"成员分隔线");
  }
  const invite=card(m,"邀请成员",{gap:12});
  text(invite,"邀请标题","邀请成员",18,ink,{fontWeight:"600"});
  field(invite,"权限","可编辑","select");
  button(invite,"创建链接","primary",{width:"fill_container"});
  const danger=card(m,"删除工作区",{gap:10});
  text(danger,"危险标题","删除工作区",17,red,{fontWeight:"600"});
  button(danger,"删除工作区","danger",{width:130});
}
function stateBoard(){
  const board=Insert(document,{type:"frame",name:pageTitle+" / 状态与确认",x:0,y:1800,width:620,height:key==="cash-import"?1060:key==="cash-categories"?1030:key==="workspace-management"?960:key==="login"?930:800,layout:"vertical",fill:pale,padding:24,gap:12,clip:true,placeholder:true});
  text(board,"标题",pageTitle+"状态",20,ink,{fontWeight:"600"});
  const states={
    "cash-import":["正在检查文件…","尚未选择账单文件","文件无法读取，请重新选择","导入完成","未选择文件时，下一步不可用"],
    "cash-categories":["正在读取分类…","暂无分类","分类暂时无法加载，请重试","分类已保存","未填写分类名称时，保存不可用"],
    "investment-holdings":["正在读取持仓…","暂无持仓","持仓暂时无法加载，请重试","报价已刷新","刷新期间按钮不可用"],
    "investment-events":["正在读取事件…","没有匹配的投资事件","投资事件暂时无法加载，请重试","筛选已更新","加载期间按钮不可用"],
    "workspace-management":["正在读取工作区信息…","暂无其他成员","工作区信息暂时无法加载，请重试","工作区名称已保存","名称未变化时，保存不可用"],
    login:["正在登录…","请输入邮箱和密码","邮箱或密码不正确，请重试","已登录","信息未填完时，登录不可用"],
    "workspace-entry":["正在创建工作区…","尚未创建工作区","无法创建工作区，请重试","工作区已创建","名称为空时，创建不可用"],
    invitation:["正在确认邀请…","邀请不可用","此邀请无效或已过期","已加入工作区","接受期间按钮不可用"],
    "cash-record":["正在读取流水…","尚未填写流水","保存失败，请稍后重试","流水已保存","必填项不完整时，保存不可用"],
  }[key];
  for(const [i,label] of ["加载中","空状态","错误状态","成功反馈","禁用与焦点"].entries()){
    const body=states[i];
    const c=card(board,label,{height:label==="禁用与焦点"?140:label==="错误状态"?112:80,padding:14,gap:6});
    text(c,label+"标题",label,13,muted);
    text(c,label+"内容",body,15,label==="错误状态"?red:ink);
    if(label==="错误状态") button(c,key==="cash-record"?"保存":"重试","secondary",{width:76});
    if(label==="禁用与焦点"){
      const controls=row(c,"焦点与禁用示例",{gap:10});
      Insert(controls,{type:"ref",name:"焦点字段",ref:"D:Q2EsN",width:220,height:40,stroke:accent,strokeWidth:2,descendants:{"D:lkemR":{enabled:false},"D:YDsht":{content:"输入中"}}});
      button(controls,"保存","primary",{width:76,opacity:0.45});
    }
  }
  if(key==="cash-categories"||key==="workspace-management"){
    const confirm=card(board,"危险确认",{gap:6,padding:14});
    text(confirm,"确认标题",key==="workspace-management"?"删除工作区？":"删除分类？",16,red,{fontWeight:"600"});
    text(confirm,"确认内容",key==="workspace-management"?"将永久删除当前工作区及全部账本数据。":"该分类已被使用，删除后相关记录会变为未分类。",12,ink);
    if(key==="workspace-management") field(confirm,"输入工作区名称","家庭账本");
    const actions=row(confirm,"确认操作",{gap:8,justifyContent:"end"});
    button(actions,"取消","secondary",{width:76});
    button(actions,key==="workspace-management"?"删除工作区":"删除分类","danger",{width:130});
  }
  if(key==="cash-categories"){
    const editor=card(board,"选中分类后的编辑",{gap:10,padding:14});
    text(editor,"编辑标题","编辑分类",16,ink,{fontWeight:"600"});
    field(editor,"分类名称","餐饮");
    const actions=row(editor,"分类编辑操作",{gap:8,justifyContent:"end"});
    button(actions,"删除","secondary",{width:76});
    button(actions,"保存","primary",{width:76});
  }
  if(key==="cash-import"){
    const mapping=card(board,"映射账户",{gap:8,padding:14});
    text(mapping,"映射标题","映射账户",16,ink,{fontWeight:"600"});
    text(mapping,"文件名","statement.csv · 银行账单",13,muted);
    field(mapping,"银行卡 · 尾号 1234","日常账户","select");
    const preview=card(board,"核对流水",{gap:8,padding:14});
    text(preview,"预览标题","核对流水",16,ink,{fontWeight:"600"});
    text(preview,"预览摘要","1 笔新流水",13,muted);
    text(preview,"预览记录","咖啡店 · 2026 年 9 月 24 日 · -28.50 CNY",13,ink);
    button(preview,"确认导入","primary",{width:120});
  }
  if(key==="login"){
    const register=card(board,"注册模式",{gap:8,padding:14});
    text(register,"模式标题","注册账户",16,ink,{fontWeight:"600"});
    field(register,"邮箱","name@example.com");
    field(register,"密码","至少 12 位");
    button(register,"注册","primary",{width:100});
  }
  if(key==="invitation"){
    const invalid=card(board,"无效邀请",{gap:8,padding:14});
    text(invalid,"无效标题","邀请不可用",16,red,{fontWeight:"600"});
    button(invalid,"返回登录","secondary",{width:100});
  }
  if(key==="cash-record"){
    const confirm=card(board,"删除确认",{gap:8,padding:14});
    text(confirm,"删除标题","删除这笔流水？",16,red,{fontWeight:"600"});
    const actions=row(confirm,"删除操作",{gap:8,justifyContent:"end"});
    button(actions,"取消","secondary",{width:76});
    button(actions,"确认删除","danger",{width:110});
  }
  Update(board,{placeholder:false});
  return board;
}
const heights={"cash-import":980,"cash-categories":1250,"investment-holdings":1450,"investment-events":1500,"workspace-management":1650,login:900,"workspace-entry":900,invitation:900,"cash-record":1300};
const made=[];
for(const w of [320,375,390,414,768,1440]){
  const access=["login","workspace-entry","invitation"].includes(key);
  const s=access?accessShell(w,heights[key]):key==="cash-record"?recordShell(w,900):shell(w,w>=1440?900:heights[key]);
  if(key==="cash-import") importPage(s);
  else if(key==="cash-categories") categoriesPage(s);
  else if(key==="investment-holdings") holdingsPage(s);
  else if(key==="investment-events") eventsPage(s);
  else if(key==="workspace-management") workspacePage(s);
  else if(key==="login") loginPage(s);
  else if(key==="workspace-entry") workspaceEntryPage(s);
  else if(key==="invitation") invitationPage(s);
  else cashRecordPage(s);
  Update(s.screen,{placeholder:false});
  made.push([w,s.screen]);
}
const board=stateBoard();
for(const [w,id] of made) Export([id],"png","/tmp/finance-pen-"+key+"-"+w,{scale:1});
Export([board],"png","/tmp/finance-pen-"+key+"-states",{scale:1});
Get((n,c)=>{if(c.problems&&c.depth<5) Print("LAYOUT",c.problems,n.name,c.parentCtx?.node.name)});
Print("SCREENS",made.map(x=>x[0]).join(","));
`;
  const input = `execute({ input: ${JSON.stringify(code)} })\nsave()\nexit()\n`;
  const output = path.resolve(`design/${key}.pen`);
  const result = spawnSync("pen", ["interactive", "--in", "design/cash-ledger.pen", "--out", output], {
    input,
    encoding: "utf8",
    timeout: 180_000,
    maxBuffer: 8 * 1024 * 1024,
  });
  const log = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
  if (result.status !== 0 || !log.includes("SCREENS 320,375,390,414,768,1440") || !log.includes("Saved")) {
    throw new Error(`${key} Pen 生成失败：\n${log.slice(-6000)}`);
  }
  mkdirSync("design/previews", { recursive: true });
  for (const suffix of [320, 375, 390, 414, 768, 1440, "states"]) {
    const dir = `/tmp/finance-pen-${key}-${suffix}`;
    const image = readdirSync(dir).find((name) => name.endsWith(".png"));
    if (!image) throw new Error(`${key}/${suffix} 未导出截图`);
    copyFileSync(path.join(dir, image), `design/previews/${key}-${suffix}.png`);
  }
  console.log(`${key}: Pen 已保存，7 张截图已导出`);
  const warnings = log.split("\n").filter((line) => line.includes("LAYOUT"));
  if (warnings.length) console.log(warnings.slice(0, 30).join("\n"));
}
