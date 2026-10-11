const path = require('node:path');
const minecraft = require(path.join(__dirname, '../.tmp/mc-join/node_modules/minecraft-protocol'));
const { connect: connectRcon } = require('./rcon-smoke.cjs');
const [port, version, rconPort] = process.argv.slice(2);

function join() {
  return new Promise((resolve, reject) => {
    const client = minecraft.createClient({ host: '127.0.0.1', port: Number(port), username: 'RankBoardSmoke', auth: 'offline', version });
    client.sidebarPackets = [];
    client.on('packet', (data, meta) => {
      if (meta.name === 'scoreboard_objective' || meta.name === 'scoreboard_score') {
        client.sidebarPackets.push({ name: meta.name, data });
      }
    });
    let ready = false;
    client.on('ping', packet => client.write('pong', { id: packet.id }));
    client.on('state', state => {
      if (state === 'play' && !ready) { ready = true; resolve(client); }
    });
    client.on('error', error => { if (!ready) reject(error); });
    client.on('end', reason => { if (!ready) reject(new Error(`Login ended: ${reason}`)); });
  });
}

function expectContains(response, text, step) {
  if (!response.includes(text)) throw new Error(`${step} did not contain '${text}': ${response}`);
}

const wait = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

(async () => {
  let client;
  let rcon;
  try {
    client = await join();
    rcon = await connectRcon(rconPort);
    let listed = false;
    for (let attempt = 0; attempt < 20; attempt++) {
      const players = await rcon.command('list');
      if (players.includes('RankBoardSmoke')) { listed = true; break; }
      await wait(250);
    }
    if (!listed) throw new Error('Smoke player did not appear in the server player list.');
    console.log('JOIN_OK');

    const set = await rcon.command('leaderboard data set RankBoardSmoke elytra 9000000000000');
    expectContains(set, '90000000.0 km', 'set above vanilla int range');
    const get = await rcon.command('leaderboard data get RankBoardSmoke elytra');
    expectContains(get, '9,000,000,000,000', 'get long flight value');
    expectContains(get, '90000000.0 km', 'formatted flight value');

    client.chat('/leaderboard display show all elytra');
    await wait(2000);
    const serialize = packets => JSON.stringify(packets, (_, value) => typeof value === 'bigint' ? String(value) : value);
    expectContains(serialize(client.sidebarPackets), '90000000.0 km', 'client sidebar fixed-number format above int range');
    if (!client.sidebarPackets.some(packet => packet.name === 'scoreboard_objective' && packet.data.action === 0)) {
      throw new Error('Client did not receive the initial sidebar objective');
    }
    client.sidebarPackets.length = 0;

    const add = await rcon.command('leaderboard data add RankBoardSmoke elytra 100000');
    expectContains(add, '90000001.0 km', 'add flight distance');
    await wait(5000);
    expectContains(serialize(client.sidebarPackets), '90000001.0 km', 'client sidebar delta refresh');
    if (client.sidebarPackets.some(packet => packet.name === 'scoreboard_objective' && [0, 1].includes(packet.data.action))) {
      throw new Error('A value refresh removed or recreated the sidebar objective');
    }
    console.log('SIDEBAR_KM_AND_DELTA_OK');
    const reset = await rcon.command('leaderboard data reset RankBoardSmoke elytra');
    expectContains(reset, '已恢复', 'reset metric override');
    const restored = await rcon.command('leaderboard data get RankBoardSmoke elytra');
    expectContains(restored, '0.0 km', 'restore underlying flight value');

    console.log('FLIGHT_LEADERBOARD_OK');
  } finally {
    if (client) client.end('flight leaderboard smoke complete');
    if (rcon) rcon.socket.end();
  }
})().catch(error => { console.error(error); process.exit(1); });
