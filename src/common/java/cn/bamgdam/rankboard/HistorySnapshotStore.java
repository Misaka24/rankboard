package cn.bamgdam.rankboard;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

final class HistorySnapshotStore {
    private static final int MAGIC=0x52424853, VERSION=1, CACHE_MONTHS=3, MAX_DAYS=32, MAX_PLAYERS=1_000_000, MAX_METRICS=1_000;
    record Snapshot(Map<UUID,Map<RankBoardMod.Metric,Long>> players, boolean partial) {}
    private final Path directory;
    private final LinkedHashMap<YearMonth,Map<LocalDate,Snapshot>> cache=new LinkedHashMap<>(4,.75f,true){
        protected boolean removeEldestEntry(Map.Entry<YearMonth,Map<LocalDate,Snapshot>> e){return size()>CACHE_MONTHS;}
    };

    HistorySnapshotStore(Path directory){this.directory=directory;}
    synchronized Optional<Snapshot> get(LocalDate date)throws IOException{return Optional.ofNullable(month(YearMonth.from(date)).get(date));}
    synchronized void put(LocalDate date,Map<UUID,Map<RankBoardMod.Metric,Long>> players,boolean partial)throws IOException{
        YearMonth ym=YearMonth.from(date); Map<LocalDate,Snapshot> data=new HashMap<>(month(ym));
        data.put(date,new Snapshot(copy(players),partial)); write(ym,data); cache.put(ym,data);
    }
    synchronized void importLegacy(Map<LocalDate,Map<UUID,Map<RankBoardMod.Metric,Long>>> snapshots,Set<LocalDate> partial)throws IOException{
        Map<YearMonth,Map<LocalDate,Snapshot>> groups=new TreeMap<>();
        snapshots.forEach((date,players)->groups.computeIfAbsent(YearMonth.from(date),x->new HashMap<>())
                .put(date,new Snapshot(copy(players),partial.contains(date))));
        for(var group:groups.entrySet()){Map<LocalDate,Snapshot> data=new HashMap<>(month(group.getKey()));
            data.putAll(group.getValue()); write(group.getKey(),data); cache.put(group.getKey(),data);}
    }
    synchronized Map.Entry<LocalDate,Snapshot> firstWithMetric(LocalDate from,LocalDate to,RankBoardMod.Metric metric)throws IOException{
        for(LocalDate date=from;!date.isAfter(to);date=date.plusDays(1)){Snapshot s=month(YearMonth.from(date)).get(date);
            if(s!=null&&has(s.players(),metric))return new AbstractMap.SimpleImmutableEntry<>(date,s);} return null;
    }
    synchronized Map.Entry<LocalDate,Snapshot> earliest(RankBoardMod.Metric metric)throws IOException{
        if(!Files.isDirectory(directory))return null; List<Path> files=new ArrayList<>();
        try(DirectoryStream<Path> stream=Files.newDirectoryStream(directory,"????-??.dat")){stream.forEach(files::add);}
        files.sort(Comparator.comparing(p->p.getFileName().toString()));
        for(Path file:files){YearMonth ym;try{ym=YearMonth.parse(file.getFileName().toString().substring(0,7));}catch(RuntimeException e){continue;}
            List<Map.Entry<LocalDate,Snapshot>> entries=new ArrayList<>(month(ym).entrySet());entries.sort(Map.Entry.comparingByKey());
            for(var entry:entries)if(metric==null||has(entry.getValue().players(),metric))return entry;} return null;
    }
    Path directory(){return directory;}

    private Map<LocalDate,Snapshot> month(YearMonth ym)throws IOException{
        Map<LocalDate,Snapshot> hit=cache.get(ym);if(hit!=null)return hit;
        Map<LocalDate,Snapshot> data=new HashMap<>();Path file=file(ym);
        if(Files.isRegularFile(file))try(DataInputStream in=new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))){
            if(in.readInt()!=MAGIC)throw new IOException("Invalid history file: "+file);
            int version=in.readInt();if(version!=VERSION)throw new IOException("Unsupported history version "+version+": "+file);
            if(!YearMonth.parse(in.readUTF()).equals(ym))throw new IOException("History month mismatch: "+file);
            for(int i=0,n=count(in.readInt(),MAX_DAYS,"days",file);i<n;i++){LocalDate date=LocalDate.ofEpochDay(in.readLong());
                if(!YearMonth.from(date).equals(ym))throw new IOException("History date outside month: "+file);
                boolean partial=in.readBoolean();Map<UUID,Map<RankBoardMod.Metric,Long>> players=new HashMap<>();
                for(int p=0,pn=count(in.readInt(),MAX_PLAYERS,"players",file);p<pn;p++){UUID uuid=new UUID(in.readLong(),in.readLong());
                    Map<RankBoardMod.Metric,Long> values=new EnumMap<>(RankBoardMod.Metric.class);
                    for(int m=0,mn=count(in.readUnsignedShort(),MAX_METRICS,"metrics",file);m<mn;m++){String name=in.readUTF();long value=in.readLong();
                        try{values.put(RankBoardMod.Metric.valueOf(name),value);}catch(IllegalArgumentException ignored){}}
                    players.put(uuid,values);}data.put(date,new Snapshot(players,partial));}
        }catch(EOFException e){throw new IOException("Truncated history file: "+file,e);}
        cache.put(ym,data);return data;
    }
    private void write(YearMonth ym,Map<LocalDate,Snapshot> data)throws IOException{
        Files.createDirectories(directory);Path target=file(ym),temp=target.resolveSibling(target.getFileName()+".tmp");
        try{try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temp))))){
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeUTF(ym.toString());out.writeInt(data.size());
            List<Map.Entry<LocalDate,Snapshot>> days=new ArrayList<>(data.entrySet());days.sort(Map.Entry.comparingByKey());
            for(var day:days){out.writeLong(day.getKey().toEpochDay());out.writeBoolean(day.getValue().partial());out.writeInt(day.getValue().players().size());
                for(var player:day.getValue().players().entrySet()){out.writeLong(player.getKey().getMostSignificantBits());out.writeLong(player.getKey().getLeastSignificantBits());
                    out.writeShort(player.getValue().size());for(var metric:player.getValue().entrySet()){out.writeUTF(metric.getKey().name());out.writeLong(metric.getValue());}}}}
            try{Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
    }
    private Path file(YearMonth ym){return directory.resolve(ym+".dat");}
    private static int count(int n,int max,String what,Path file)throws IOException{if(n<0||n>max)throw new IOException("Invalid "+what+" count in "+file+": "+n);return n;}
    private static boolean has(Map<UUID,Map<RankBoardMod.Metric,Long>> players,RankBoardMod.Metric metric){return players.values().stream().anyMatch(v->v.containsKey(metric));}
    private static Map<UUID,Map<RankBoardMod.Metric,Long>> copy(Map<UUID,Map<RankBoardMod.Metric,Long>> players){
        Map<UUID,Map<RankBoardMod.Metric,Long>> result=new HashMap<>();players.forEach((uuid,values)->result.put(uuid,new EnumMap<>(values)));return result;}
}