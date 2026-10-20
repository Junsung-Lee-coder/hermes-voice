# Marking a Hermes session for Hermes Voice (`created_source`)

This is for the person who administers the Hermes install. Hermes Voice never changes the server; it only reads `created_source`.
Nothing here is run by the app, and the commands below name placeholders, not a real session.

## What you are changing

| Column | Meaning | Who changes it |
| --- | --- | --- |
| `sessions.source` | where the session is routed now (`discord`, `tui`, `recorder-phone`, ...) | Hermes and its clients, any time |
| `sessions.created_source` | where the session began; written once at insert and kept | nobody, normally |

Hermes Voice lists and uses a session only when `created_source = 'recorder-phone'`. That is the whole test: `source` is neither a permission nor a veto, and a local registration, an earlier conversation of this app or the app's own `source` value never stands in for a missing or different `created_source`. The phone's registry only keeps each session's voice name and archived state; the app fills it on the next refresh or launch. Setting the column by hand **overrides the record of where the session began**. Keep the old value so you can put it back. Leave `source`, `session_key`, the Discord peer or mapping and all message rows alone.

Do not mark scheduled-job (`cron`), Kanban, sub-agent, routing (`recorder-phone-router`) or other internal sessions.

## Sessions made by earlier builds

A conversation made by an earlier build of this app whose `created_source` is empty (NULL) or different is not listed and not usable until it is marked here, even if its `source` is still `recorder-phone`. The steps below apply to it unchanged. Conversations made by this version carry the marker from the start. The app sends no `source` filter to the dashboard; the dashboard's own list leaves out hidden rows (such as the app's routing session), sub-agent runs and compression continuations, and the app lists what comes back.

## Compression: mark the root

When Hermes compresses a conversation, the dashboard list shows the root session under the id of its newest part, and takes `source` and `created_source` from the root. The newest part gets its own `created_source` (the `source` at the time of compression). The app reads the marker from the root of the compression chain, so mark the root, not a later part. The query in step 2 finds it.

## Steps

Set the placeholders for your install (the state database lives in the Hermes home of the profile the dashboard serves):

```bash
DB="$HERMES_HOME/state.db"          # the profile's state database
ID='SESSION_ID_FROM_THE_DASHBOARD'  # exact id; letters, digits, . _ : -
MARKER='recorder-phone'
[[ "$ID" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$ ]] || { echo "bad id"; exit 1; }
```

1. **Back up first** (safe while Hermes runs) and keep the provenance of the row:

   ```bash
   BACKUP="$DB.before-marker-$(date +%Y%m%d%H%M%S)"
   sqlite3 "$DB" ".backup '$BACKUP'" && sqlite3 "$BACKUP" 'PRAGMA integrity_check;'
   ```

2. **Look at the row and find the compression root** (read only):

   ```bash
   sqlite3 -header -column "$DB" <<SQL
   .parameter init
   .parameter set :id '$ID'
   WITH RECURSIVE up(id, depth) AS (
     SELECT id, 0 FROM sessions WHERE id = :id
     UNION ALL
     SELECT p.id, up.depth + 1
     FROM up JOIN sessions c ON c.id = up.id JOIN sessions p ON p.id = c.parent_session_id
     WHERE p.end_reason = 'compression' AND p.ended_at IS NOT NULL AND c.started_at >= p.ended_at AND up.depth < 64)
   SELECT s.id, s.source, s.created_source, s.end_reason, s.parent_session_id, s.title, up.depth AS steps_from_given_id
   FROM up JOIN sessions s ON s.id = up.id ORDER BY up.depth;
   SQL
   ```

   The last row (largest `steps_from_given_id`) is the root. Use its id as `ROOT` below. Note its current `created_source` as `OLD` (empty means NULL). Stop if its `source` is `cron`, `kanban` or `recorder-phone-router`.

3. **Set the marker in one transaction** that touches exactly one row and only if the value is still what you saw:

   ```bash
   ROOT='ROOT_ID_FROM_STEP_2'; OLD=''   # OLD='' for NULL, else the exact old text
   sqlite3 "$DB" <<SQL
   .timeout 10000
   .parameter init
   .parameter set :root '$ROOT'
   .parameter set :marker '$MARKER'
   .parameter set :old '$OLD'
   BEGIN IMMEDIATE;
   UPDATE sessions SET created_source = :marker
    WHERE id = :root AND source NOT IN ('cron', 'kanban', 'recorder-phone-router')
      AND COALESCE(created_source, '') = :old;
   SELECT changes() AS rows_changed;   -- must print 1
   COMMIT;
   SQL
   ```

   If `rows_changed` is not 1, run `ROLLBACK;` (or just do not commit) and look again. Nothing else is written.

4. **Check.** `GET /api/sessions/ROOT` on the dashboard shows `created_source` as `recorder-phone`, `source` unchanged. In the app, Refresh (or relaunch) shows the session with its existing history and makes it a voice destination. The app does not add archived sessions, stops at 100 active conversations, and reads the dashboard's sessions page by page to the end of its list, showing each conversation as soon as its page has been read.

## Roll back

Take the marker off again (the next send, voice request or refresh no longer accepts it; the phone keeps its entry, which holds its place among the 100 and its voice name until the marker is back):

```bash
sqlite3 "$DB" <<SQL
.timeout 10000
.parameter init
.parameter set :root '$ROOT'
.parameter set :marker '$MARKER'
.parameter set :old '$OLD'
BEGIN IMMEDIATE;
UPDATE sessions SET created_source = NULLIF(:old, '') WHERE id = :root AND created_source = :marker;
SELECT changes() AS rows_changed;   -- must print 1
COMMIT;
SQL
```

Restoring the whole backup is the last resort, because it also drops everything written to the database since. A turn the app already accepted is not cancelled by changing the marker.

## Limits

- `created_source` is not an access right and not proof that the phone app created the session; it only makes the session eligible here.
- On a server whose sessions have no `created_source` value the app shows no conversation at all, including ones made in this app, and nothing can be marked there.
- The app never changes `created_source` or `source` on the server and has no import or picker for existing sessions.
- An id selected on the phone before a compression is not moved to the newer part automatically.
