// Exercise comparison commands; network mode also checks delivered click events.
const fs = require('node:fs');
const path = require('node:path');
const minecraft = require(path.join(__dirname, '../.tmp/mc-join/node_modules/minecraft-protocol'));
const { connect } = require('./rcon-smoke.cjs');
const [port, version, rconPort, directory, gameVersion, mode] = process.argv.slice(2);
const headless = mode === 'headless';
const uuid = '00000000-0000-4000-8000-000000000001';
const statsDirectory = Number(gameVersion.split('.')[0]) >= 26
  ? 'world/players/stats' : 'world/stats';
const file = path.join(directory, statsDirectory, `${uuid}.json`);
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const serialize = value => JSON.stringify(value, (_, v) => typeof v === 'bigint' ? String(v) : v);
function expect(text, pattern, step) {
  if (!pattern.test(text)) throw new Error(`${step}: ${text}`);
}
function writeJump(value) {
  const stats = JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
  stats.stats['minecraft:custom']['minecraft:jump'] = value;
  fs.writeFileSync(file, JSON.stringify(stats));
}

(async () => {
  let client, rcon;
  const deadline = setTimeout(() => { console.error('Comparison regression timed out'); process.exit(1); }, 90000);
  try {
    const packets = [];
    if (!headless) {
      client = minecraft.createClient({host:'127.0.0.1', port:Number(port), username:'RankBoardSmoke', auth:'offline', version});
      client.on('packet', (data, meta) => {
        if (meta.name === 'system_chat') packets.push(data);
      });
      client.on('ping', packet => client.write('pong', {id:packet.id}));
      client.on('position', packet => client.write('teleport_confirm', {teleportId:packet.teleportId}));
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('Login timed out')), 30000);
        client.on('state', state => { if (state === 'play') { clearTimeout(timer); resolve(); } });
        client.on('error', error => { clearTimeout(timer); reject(error); });
      });
    }
    rcon = await connect(rconPort);
    if (!headless) {
      for (let attempt = 0; attempt < 40; ++attempt) {
        if ((await rcon.command('list')).includes('RankBoardSmoke')) break;
        if (attempt === 39) throw new Error('Player did not enter world');
        await wait(250);
      }
      console.log('JOIN_OK');
      await rcon.command('op RankBoardSmoke');
    }
    await rcon.command('leaderboard whitelist false');
    expect(await rcon.command('leaderboard all playtime'), /AllowedSmoke/, 'seeded offline cache');
    async function compare() {
      packets.length = 0;
      // execute-as keeps RCON as the output sink; use a real player command so
      // click events are delivered to the connected client and can be inspected.
      if (headless) await rcon.command('leaderboard cache compare AllowedSmoke');
      else client.chat('/leaderboard cache compare AllowedSmoke');
      for (let attempt = 0; attempt < 60; ++attempt) {
        const match = headless
          ? (await rcon.command('rankboard_test_report')).match(/REPORT_ID ([0-9a-f-]{36})/)
          : serialize(packets).match(/\/leaderboard cache compare list ([0-9a-f-]{36}) 1/);
        if (match) return match[1];
        await wait(100);
      }
      throw new Error(`Comparison report missing: ${serialize(packets)}`);
    }
    const command = (action, id, player = false) => rcon.command(
      `leaderboard cache compare ${action} ${id}${player ? ` ${uuid}` : ''}`);
    writeJump(123456);
    let id = await compare();
    expect(await rcon.command(`leaderboard cache compare list ${id} 1`), /AllowedSmoke/, 'difference list');
    expect(await command('player', id, true), /123,456/, 'detailed old/new values');
    expect(await command('confirm-player', id, true), /先点击|Click the apply/, 'confirmation required');
    const previewRanking = await rcon.command('leaderboard all jumps');
    expect(previewRanking, /AllowedSmoke/, 'preview preserves cached player');
    if (/123,?456/.test(previewRanking)) throw new Error('Preview changed the cache before confirmation');
    expect(await command('apply-player', id, true), /确认|Confirm/, 'confirmation prompt');
    expect(await command('confirm-player', id, true), /已将玩家|Applied the reread/, 'single-player apply');
    expect(await rcon.command('leaderboard all jumps'), /123,?456/, 'applied ranking');
    expect(await command('confirm-player', id, true), /过期|expired/, 'report is single use');
    console.log('COMPARISON_CONFIRMATION_OK');

    writeJump(234567);
    id = await compare();
    await command('apply-player', id, true);
    writeJump(345678);
    expect(await command('confirm-player', id, true), /数据已变化|data changed/, 'stale-file protection');
    expect(await rcon.command('leaderboard all jumps'), /123,?456/, 'stale result cannot overwrite cache');
    console.log('COMPARISON_STALE_PROTECTION_OK');

    id = await compare();
    expect(await command('confirm-all', id), /先点击|Click the apply/, 'bulk confirmation required');
    await command('apply-all', id);
    expect(await command('confirm-all', id), /已将|Applied reread/, 'confirmed bulk apply');
    expect(await rcon.command('leaderboard all jumps'), /345,?678/, 'bulk ranking update');
    writeJump(456789);
    id = await compare();
    await command('cancel', id);
    expect(await rcon.command('leaderboard all jumps'), /345,?678/, 'cancel preserves cache');
    fs.unlinkSync(file);
    id = await compare();
    expect(await command('apply-all', id), /不完整|incomplete/, 'missing files block bulk apply');
    expect(await command('apply-player', id, true), /没有可用|No usable/, 'missing files block individual apply');
    console.log('CACHE_COMPARISON_OK');
  } finally {
    clearTimeout(deadline);
    if (client) client.end('cache comparison regression complete');
    if (rcon) rcon.socket.end();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
