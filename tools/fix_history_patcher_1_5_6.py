from pathlib import Path

path = Path("tools/patch_history_1_5_6.py")
text = path.read_text(encoding="utf-8")
old = """    text = one(text, list_line, '        nbt.putString(\"historySchema\", Integer.toString(HISTORY_SCHEMA));\\n'+list_line, path+' schema save')
"""
new = """    count = text.count(list_line)
    print(path+' schema save', count)
    if count < 1: raise RuntimeError(path+' schema save: '+str(count))
    text = text.replace(list_line,
        '        nbt.putString(\"historySchema\", Integer.toString(HISTORY_SCHEMA));\\n'+list_line, 1)
"""
count = text.count(old)
if count != 1:
    raise RuntimeError(f"history patcher anchor count: {count}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
