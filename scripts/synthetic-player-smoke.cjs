// Test-only server-internal player lifecycle; this is not a TCP login test.
const { connect } = require('./rcon-smoke.cjs');
(async () => {
  const rcon = await connect(process.argv[2]);
  const expect = (text, marker) => { if (!text.includes(marker)) throw new Error(text); };
  try {
    await rcon.command('leaderboard config set avatar-cache-enabled false');
    await rcon.command('leaderboard config set scoreboard-recipient-filter false');
    for (let index = 0; index < 5; index++) {
      expect(await rcon.command('rankboard_test_player join'), 'SYNTHETIC_JOIN_OK');
      await rcon.command('execute as RankBoardProbe run leaderboard display show all jumps');
      expect(await rcon.command('rankboard_test_player check'), 'SYNTHETIC_SIDEBAR_OK');
      expect(await rcon.command('leaderboard all jumps'), 'RankBoardProbe');
      expect(await rcon.command('rankboard_test_player leave'), 'SYNTHETIC_LEAVE_OK');
      if ((await rcon.command('list')).includes('RankBoardProbe')) throw new Error('Player was not removed');
    }
    console.log('SYNTHETIC_PLAYER_OK (five joins, sidebar packets, leaves; no TCP client)');
  } finally { rcon.socket.end(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
