const path = require('node:path');
const minecraft = require(path.join(__dirname, '../.tmp/mc-join/node_modules/minecraft-protocol'));
const { connect } = require('./rcon-smoke.cjs');
const [port, version, rconPort, webPort] = process.argv.slice(2);
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const serialize = value => JSON.stringify(value, (_, v) => typeof v === 'bigint' ? String(v) : v);
function expect(value, fragment, step) {
  if (!value.includes(fragment)) throw new Error(`${step}: expected ${fragment}, received ${value}`);
}
function rawValue(response) {
  const match = response.match(/[：:]\s*([\d,]+)（/);
  if (!match) throw new Error(`Could not parse metric value: ${response}`);
  return Number(match[1].replaceAll(',', ''));
}

async function join() {
  const client = minecraft.createClient({host:'127.0.0.1', port:Number(port), username:'RankBoardSmoke', auth:'offline', version});
  client.packets = [];
  client.on('packet', (data, meta) => {
    if (['scoreboard_team', 'teams', 'player_chat', 'system_chat', 'scoreboard_objective', 'scoreboard_score'].includes(meta.name)) {
      client.packets.push({name:meta.name, data});
    }
  });
  client.on('ping', packet => client.write('pong', {id:packet.id}));
  client.on('position', packet => client.write('teleport_confirm', {teleportId:packet.teleportId}));
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Login timeout')), 30000);
    client.on('state', state => { if (state === 'play') { clearTimeout(timer); resolve(); } });
    client.on('error', error => { clearTimeout(timer); reject(error); });
  });
  return client;
}

async function api(query) {
  const response = await fetch(`http://127.0.0.1:${webPort}/api/rankings?${query}`, {
    headers:{'X-Forwarded-For':`feature-parity-${++api.counter}`}
  });
  if (!response.ok) throw new Error(`Ranking API: ${response.status} ${await response.text()}`);
  return response.json();
}
api.counter = 0;

(async () => {
  let client, rcon;
  try {
    client = await join();
    rcon = await connect(rconPort);
    for (let attempt=0; attempt<30; attempt++) {
      if ((await rcon.command('list')).includes('RankBoardSmoke')) break;
      if (attempt===29) throw new Error('Joined player missing from server');
      await wait(250);
    }
    console.log('JOIN_OK');
    await wait(1000);
    expect(serialize(client.packets), '查看排行榜单相关内容', 'simple join prompt');
    for (const key of ['metric-label-breeding', 'metric-label-afk', 'metric-label-bedrock',
      'metric-color-breeding', 'metric-color-afk', 'web-port-fallback-enabled', 'web-switcher-op-hint-enabled']) {
      expect(await rcon.command(`leaderboard config get ${key}`), key, 'synced configuration');
    }
    await rcon.command('op RankBoardSmoke');
    const asPlayer = command => rcon.command(`execute as RankBoardSmoke run ${command}`);
    await asPlayer('leaderboard joinmenu false');
    expect(await asPlayer('leaderboard joinmenu status'), '关闭', 'per-player join menu');
    await asPlayer('leaderboard joinmenu true');
    await rcon.command('leaderboard help config general');
    await asPlayer('leaderboard intro');
    await rcon.command('leaderboard bedrock true');
    await rcon.command('leaderboard config set client-scoreboard-show-zero true');
    await rcon.command('leaderboard namecolor true');
    await asPlayer('leaderboard display show all playtime');
    await rcon.command('leaderboard namecolor scoreboard-only');
    expect(await rcon.command('leaderboard namecolor status'), 'scoreboard-only', 'name color mode');
    await rcon.command('team add externalname');
    await rcon.command('team modify externalname color green');
    await rcon.command('team modify externalname prefix {"text":"EXTERNAL ","color":"green"}');
    await rcon.command('team join externalname RankBoardSmoke');
    client.packets.length=0;
    for (const metric of ['playtime', 'mined', 'elytra', 'afk', 'breeding', 'bedrock']) {
      await asPlayer(`leaderboard display show all ${metric}`);
    }
    await rcon.command('leaderboard config set carousel-interval-seconds 3');
    await asPlayer('leaderboard carousel true');
    await wait(7500);
    expect(await rcon.command('team list externalname'), 'RankBoardSmoke', 'foreign team survives switches and carousel');
    const teamPackets=client.packets.filter(p=>['scoreboard_team','teams'].includes(p.name));
    for (const packet of teamPackets) {
      const content=serialize(packet);
      if (content.includes('rbc_') && content.includes('RankBoardSmoke')) throw new Error(`RankBoard changed player team: ${content}`);
    }
    client.chat('COLOR_REGRESSION');
    await wait(500);
    expect(serialize(client.packets.filter(p=>p.name==='player_chat')), 'EXTERNAL', 'chat preserves foreign name prefix');
    console.log('NAME_COLOR_ISOLATION_OK');
    await rcon.command('leaderboard data set RankBoardSmoke breeding 7');
    if (rawValue(await rcon.command('leaderboard data get RankBoardSmoke breeding')) !== 7) throw new Error('Breeding override mismatch');
    await rcon.command('leaderboard data set RankBoardSmoke afk 72000');
    expect(await rcon.command('leaderboard data get RankBoardSmoke afk'), '1h 0m', 'AFK duration');
    await rcon.command('leaderboard data reset RankBoardSmoke afk');
    // Tick the actual server beyond the AFK threshold instead of waiting three real minutes.
    await rcon.command('gamemode spectator RankBoardSmoke');
    await rcon.command('tick sprint 3800t');
    let afk;
    for (let attempt=0; attempt<60; attempt++) {
      await wait(1000);
      afk=await rcon.command('leaderboard data get RankBoardSmoke afk');
      if (rawValue(afk)>=3600) break;
    }
    if (rawValue(afk) < 3600) throw new Error(`AFK tracker did not accumulate: ${afk}`);
    await rcon.command('tick sprint stop');
    await rcon.command('execute at RankBoardSmoke run setblock ~2 ~ ~ bedrock');
    await wait(1500);
    await rcon.command('execute at RankBoardSmoke run setblock ~2 ~ ~ air');
    await wait(1500);
    if (rawValue(await rcon.command('leaderboard data get RankBoardSmoke bedrock')) !== 1) throw new Error('Bedrock tracker did not record exactly one removed block');
    const ranking=await api('period=all&metric=breeding');
    expect(serialize(ranking), 'RankBoardSmoke', 'ranking API includes breeding player');
    await wait(1100);
    expect(serialize(await api('period=all&metric=afk')), '摸鱼榜', 'ranking API AFK');
    await wait(1100);
    expect(serialize(await api('period=all&metric=bedrock')), '破基岩榜', 'ranking API bedrock');
    await rcon.command('leaderboard namecolor false');
    await asPlayer('leaderboard display show all mined');
    expect(await rcon.command('team list externalname'), 'RankBoardSmoke', 'disabled name colors preserve external team');
    await rcon.command('save-all');
    console.log('FEATURE_SYNC_OK');
  } finally {
    if (client) client.end('feature parity smoke complete');
    if (rcon) rcon.socket.end();
  }
})().catch(error=>{console.error(error); process.exitCode=1;});
