from pathlib import Path
import re
import sqlite3

java = Path(__file__).resolve().parents[1] / 'src/main/kotlin/com/example/phonelab/systems/PhoneStore.kt'
source = java.read_text()
schema = re.findall(r'const val SCHEMA_[A-Z]+ = "([^"]+)"', source)
assert len(schema) == 6
db = sqlite3.connect(':memory:')
db.execute('PRAGMA foreign_keys = ON')
for statement in schema:
    db.execute(statement)
lead = ('world', 'chaptered', 'lilly', 'bakugo')
npc = ('world', 'chaptered', 'lilly', 'mika')
other = ('world', 'alternate', 'lilly', 'bakugo')
for key, role in [(lead, 'LEAD'), (npc, 'NPC'), (other, 'LEAD')]:
    db.execute('INSERT INTO phone_contact VALUES(?,?,?,?,?,?)', (*key, role, 'Same display name'))
db.execute('INSERT INTO phone_text(world,campaign,persona,contact,id,chapter,speaker,body,stamp) VALUES(?,?,?,?,?,?,?,?,?)',
           (*lead, 'm1', 2, 'CONTACT', 'Rooftop at eight', 1))
db.execute('INSERT INTO phone_carry VALUES(?,?,?,?,?,?,?,?)', (*lead, 'carry1', 'STORY_MEMORY', 'Meet at eight', 2))
db.execute('INSERT INTO phone_source VALUES(?,?,?,?,?,?,?)', (*lead, 'carry1', 'm1', 0))
for key in (other, npc):
    db.execute('INSERT INTO phone_carry VALUES(?,?,?,?,?,?,?,?)', (*key, 'carry1', 'STORY_MEMORY', 'Other note', 2))
    try:
        db.execute('INSERT INTO phone_source VALUES(?,?,?,?,?,?,?)', (*key, 'carry1', 'm1', 0))
        raise AssertionError('Cross-thread source was accepted')
    except sqlite3.IntegrityError:
        pass
db.execute('DELETE FROM phone_carry WHERE world=? AND campaign=? AND persona=? AND contact=? AND id=?', (*lead, 'carry1'))
assert db.execute('SELECT COUNT(*) FROM phone_source').fetchone() == (0,)
assert db.execute('SELECT COUNT(*) FROM phone_text').fetchone() == (1,)
print('Phone schema and scoped foreign-key checks passed')
