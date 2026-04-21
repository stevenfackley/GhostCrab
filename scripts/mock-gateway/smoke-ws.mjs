import WebSocket from "ws";

const ws = new WebSocket("ws://localhost:18790/ws");
let idSeq = 0;
const nextId = () => ++idSeq;

function call(method, params) {
  return new Promise((resolve, reject) => {
    const id = nextId();
    const onMessage = (data) => {
      const msg = JSON.parse(data.toString());
      if (msg.id === id) {
        ws.off("message", onMessage);
        if (msg.error) reject(msg.error);
        else resolve(msg.result);
      } else if (msg.method === "skills.install.progress") {
        console.log("  progress:", msg.params);
      }
    };
    ws.on("message", onMessage);
    ws.send(JSON.stringify({ jsonrpc: "2.0", id, method, params }));
  });
}

ws.on("open", async () => {
  console.log("connected");
  console.log("whoami →", await call("auth.whoami", null));
  console.log("skills.list →", await call("skills.list", null));
  console.log("installing new/cool-skill ...");
  const installed = await call("skills.install", { source: "clawhub", slug: "new/cool-skill", force: false });
  console.log("skills.install →", installed);
  console.log("skills.list (after) →", await call("skills.list", null));
  console.log("uninstalling ...");
  await call("skills.uninstall", { slug: "new/cool-skill" });
  console.log("skills.list (after uninstall) →", await call("skills.list", null));
  ws.close();
});
ws.on("close", () => process.exit(0));
ws.on("error", e => { console.error("err", e.message); process.exit(1); });
setTimeout(() => { console.error("timeout"); process.exit(1); }, 10_000);
