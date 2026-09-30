import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";

const file = "design/finance-ui.lib.pen";
const existing = JSON.parse(readFileSync(file, "utf8")).children
  .filter((node) => ["Finance/DataTable/InvestmentHoldings", "Finance/DataTable/InvestmentEvents"].includes(node.name))
  .map((node) => node.id);

const code = String.raw`
for (const id of ${JSON.stringify(existing)}) Delete(id);
function text(parent, name, content, width, color="#202124", weight="normal") {
  return Insert(parent,{type:"text",name,content,width,textGrowth:"fixed-width",fontFamily:"Roboto",fontSize:12,fontWeight:weight,fill:color});
}
function row(parent, name, height, fill) {
  return Insert(parent,{type:"frame",name,layout:"horizontal",width:"fill_container",height,alignItems:"center",fill,padding:[0,14],gap:8});
}
function table(name, headers, widths, values) {
  const height=38+values.length*70+values.length-1;
  const root=Insert(document,{type:"frame",name,reusable:true,x:1400,y:name.endsWith("Holdings")?380:600,width:1136,height,layout:"vertical",fill:"#ffffff",cornerRadius:8,clip:true});
  const head=row(root,"表头",38,"#efeff0");
  headers.forEach((label,i)=>text(head,label+"列",label,widths[i],"#6f7279","600"));
  values.forEach((cells,index)=>{
    const body=row(root,"第 "+(index+1)+" 行",70,"#ffffff");
    cells.forEach((value,i)=>text(body,headers[i]+"值",value,widths[i],i===0?"#202124":i===cells.length-1?"#14865a":"#52565d"));
    if(index<values.length-1) Insert(root,{type:"rectangle",name:"行分隔线",width:"fill_container",height:1,fill:"#e4e4e7"});
  });
  return root;
}
table("Finance/DataTable/InvestmentHoldings",
  ["标的 / 账户","当前单价 / 平均成本","数量","当前市值","仓位","浮盈亏","近 24 小时"],
  [220,218,80,134,98,142,168],
  [["Apple · AAPL\n证券账户","110 USD / 100 USD","2","220 USD","+100%","+20 USD\n+10%","+8 USD\n+4%"]]);
table("Finance/DataTable/InvestmentEvents",
  ["发生时间","账户","事件","资产变化","手续费","备注","查看"],
  [160,120,84,320,94,212,70],
  [["9 月 24 日 10:30","证券账户","买入","流出 -220 USD\n流入 +2 AAPL","1 USD","买入苹果","查看"],
   ["9 月 24 日 10:30","证券账户","买入","流出 -220 USD\n流入 +2 AAPL","1 USD","第二条投资事件","查看"]]);
Print("TABLES");
`;
const input = `execute({ input: ${JSON.stringify(code)} })\nsave()\nexit()\n`;
const result = spawnSync("pen", ["interactive", "--in", file, "--out", file], {
  input, encoding: "utf8", timeout: 180_000, maxBuffer: 8 * 1024 * 1024,
});
const output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
if (result.status !== 0 || !output.includes("TABLES")) throw new Error(output);
process.stdout.write(output);
