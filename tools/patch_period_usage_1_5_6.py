from pathlib import Path


def r(p): return Path(p).read_text(encoding='utf-8')
def w(p,t): Path(p).write_text(t,encoding='utf-8')
def one(t,a,b,l):
 c=t.count(a); print(l,c)
 if c!=1: raise RuntimeError(f'{l}: {c}')
 return t.replace(a,b,1)

for path in ['src/main/java/cn/bamgdam/rankboard/RankBoardMod.java','src/mojmap/java/cn/bamgdam/rankboard/RankBoardMod.java']:
 t=r(path)
 t=one(t,'        state.rollPeriods(server);\n        return StatReader.readAll(server, metric).stream()\n',
   '        state.rollPeriods(server);\n        if (period != Period.ALL && !state.isPeriodComplete(period)) {\n            throw new IllegalStateException(period.label + "统计没有完整周期边界；服务器需在周期开始时在线并完成统计扫描");\n        }\n        return StatReader.readAll(server, metric).stream()\n',path+' entries')
 w(path,t)

for path in ['src/main/java/cn/bamgdam/rankboard/BoardService.java','src/mojmap/java/cn/bamgdam/rankboard/BoardService.java']:
 t=r(path)
 t=one(t,'        MinecraftServer server = PlayerCompat.server(player);\n        Scoreboard scoreboard = server.getScoreboard();\n',
   '        MinecraftServer server = PlayerCompat.server(player);\n        LeaderboardState state = LeaderboardState.get(server);\n        if (period != RankBoardMod.Period.ALL && !state.isPeriodComplete(period)) {\n            throw new IllegalStateException(period.label + "统计没有完整周期边界");\n        }\n        Scoreboard scoreboard = server.getScoreboard();\n',path+' overview')
 t=one(t,'        LeaderboardState state = LeaderboardState.get(server);\n        for (RankBoardMod.Metric metric : RankBoardMod.Metric.values()) {\n',
   '        for (RankBoardMod.Metric metric : RankBoardMod.Metric.values()) {\n',path+' duplicate')
 w(path,t)

for path,players in [
 ('src/main/java/cn/bamgdam/rankboard/StatReader.java','server.getPlayerManager().getPlayerList()'),
 ('src/mojmap/java/cn/bamgdam/rankboard/StatReader.java','server.getPlayerList().getPlayers()')]:
 t=r(path)
 t=one(t,'                BoardService.restoreGlobal(server);\n                BoardService.refreshAll(server);\n',
   '                BoardService.restoreGlobal(server);\n                for (var player : '+players+') BoardService.restore(player);\n                BoardService.refreshAll(server);\n',path+' restore')
 w(path,t)
