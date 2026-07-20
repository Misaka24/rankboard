from pathlib import Path


def r(p): return Path(p).read_text(encoding='utf-8')
def w(p,t): Path(p).write_text(t,encoding='utf-8')
def one(t,a,b,l):
 c=t.count(a); print(l,c)
 if c!=1: raise RuntimeError(f'{l}: {c}')
 return t.replace(a,b,1)


def patch(path, mojmap):
 t=r(path)
 t=one(t,'HISTORY_SCHEMA = 2','HISTORY_SCHEMA = 3',path+' schema')
 t=one(t,'        LocalDate now = LocalDate.now();\n        boolean changed = false;\n',
   '        LocalDate now = LocalDate.now();\n        LocalTime boundaryTime = LocalTime.now();\n        boolean nearMidnight = !boundaryTime.isAfter(COMPLETE_BOUNDARY_LIMIT);\n        boolean changed = false;\n',path+' clock')
 t=one(t,'                PeriodData replacement = new PeriodData(period, period.key(now));\n',
   '                PeriodData replacement = new PeriodData(period, period.key(now),\n                        completePeriodBoundary(period, now, nearMidnight));\n',path+' ctor')
 t=one(t,'    public long getBaseline(RankBoardMod.Period period, UUID uuid, RankBoardMod.Metric metric) {\n        PeriodData data = periods.get(period);\n        return data == null ? 0 : data.players.getOrDefault(uuid, Map.of()).getOrDefault(metric, 0L);\n    }\n',
   '    public long getBaseline(RankBoardMod.Period period, UUID uuid, RankBoardMod.Metric metric) {\n        PeriodData data = periods.get(period);\n        return data == null ? 0 : data.players.getOrDefault(uuid, Map.of()).getOrDefault(metric, 0L);\n    }\n    public boolean isPeriodComplete(RankBoardMod.Period period) {\n        if (period == RankBoardMod.Period.ALL) return true;\n        PeriodData data = periods.get(period);\n        return data != null && data.complete;\n    }\n',path+' status')
 marker='    private static '+('ListTag writePlayers' if mojmap else 'NbtList writePlayers')
 helper='''    private static boolean completePeriodBoundary(RankBoardMod.Period period, LocalDate date, boolean nearMidnight) {
        if (!nearMidnight) return false;
        return switch (period) {
            case DAILY -> true;
            case WEEKLY -> date.getDayOfWeek() == java.time.DayOfWeek.MONDAY;
            case MONTHLY -> date.getDayOfMonth() == 1;
            case YEARLY -> date.getDayOfYear() == 1;
            case ALL -> true;
        };
    }

'''
 t=one(t,marker,helper+marker,path+' helper')
 if mojmap:
  old='''    private static final class PeriodData {
        final RankBoardMod.Period period; final String key;
        final Map<UUID, Map<RankBoardMod.Metric, Long>> players = new HashMap<>();
        PeriodData(RankBoardMod.Period period, String key) { this.period = period; this.key = key; }
        void capture(StatSnapshot snapshot) { players.put(snapshot.uuid(), snapshot.values()); }
        CompoundTag toNbt() {
            CompoundTag nbt = new CompoundTag(); nbt.putString("period", period.name()); nbt.putString("key", key);
            nbt.put("players", writePlayers(players)); return nbt;
        }
        static PeriodData fromNbt(CompoundTag nbt) {
            PeriodData data = new PeriodData(RankBoardMod.Period.valueOf(NbtCompat.getString(nbt, "period")), NbtCompat.getString(nbt, "key"));
            data.players.putAll(readPlayers(nbt));
            return data;
        }
    }'''
  typ='CompoundTag'
 else:
  old='''    private static final class PeriodData {
        final RankBoardMod.Period period; final String key;
        final Map<UUID, Map<RankBoardMod.Metric, Long>> players = new HashMap<>();
        PeriodData(RankBoardMod.Period period, String key) { this.period = period; this.key = key; }
        void capture(StatSnapshot snapshot) { players.put(snapshot.uuid(), snapshot.values()); }
        NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound(); nbt.putString("period", period.name()); nbt.putString("key", key);
            nbt.put("players", writePlayers(players)); return nbt;
        }
        static PeriodData fromNbt(NbtCompound nbt) {
            PeriodData data = new PeriodData(RankBoardMod.Period.valueOf(NbtCompat.getString(nbt, "period")), NbtCompat.getString(nbt, "key"));
            data.players.putAll(readPlayers(nbt));
            return data;
        }
    }'''
  typ='NbtCompound'
 new=f'''    private static final class PeriodData {{
        final RankBoardMod.Period period; final String key; final boolean complete;
        final Map<UUID, Map<RankBoardMod.Metric, Long>> players = new HashMap<>();
        PeriodData(RankBoardMod.Period period, String key, boolean complete) {{
            this.period = period; this.key = key; this.complete = complete;
        }}
        void capture(StatSnapshot snapshot) {{ players.put(snapshot.uuid(), snapshot.values()); }}
        {typ} toNbt() {{
            {typ} nbt = new {typ}(); nbt.putString("period", period.name()); nbt.putString("key", key);
            nbt.putBoolean("complete", complete); nbt.put("players", writePlayers(players)); return nbt;
        }}
        static PeriodData fromNbt({typ} nbt) {{
            PeriodData data = new PeriodData(RankBoardMod.Period.valueOf(NbtCompat.getString(nbt, "period")),
                    NbtCompat.getString(nbt, "key"), NbtCompat.getBoolean(nbt, "complete"));
            data.players.putAll(readPlayers(nbt));
            return data;
        }}
    }}'''
 t=one(t,old,new,path+' data')
 w(path,t)

patch('src/main/java/cn/bamgdam/rankboard/LeaderboardState.java',False)
patch('src/mojmap/java/cn/bamgdam/rankboard/LeaderboardState.java',True)
