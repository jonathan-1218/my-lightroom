-- Lumen: on-device SQLite (android.database.sqlite, no Room).
-- Single user, app-private database: no owner columns, no row-level security.
-- Times are UTC epoch milliseconds (SQLite has no timestamptz).
-- An "edit" is one JSON document (see architecture.md). Originals are never written.
-- Foreign keys are switched on in Db.onConfigure. Db splits statements at a ";" that ends a line, so keep those out of comments.
-- Changes go in migrate_<n>.sql (both apps run them in order), never in this file.

create table photos (
  id          integer primary key,
  key         text    not null unique,          -- display name + byte size: same photo via picker or gallery
  uri         text    not null,                 -- last content:// uri it was opened from
  taken_at    integer,                          -- from EXIF, null if absent
  added_at    integer not null,
  updated_at  integer not null,
  width       integer not null check (width > 0),
  height      integer not null check (height > 0),
  rating      integer not null default 0 check (rating between 0 and 5),
  flag        integer not null default 0 check (flag in (-1, 0, 1)),  -- reject, none, pick
  label       text    check (label in ('red','yellow','green','blue','purple')),
  edit        text    not null default '{"v":1}' -- current develop settings, JSON
);
create index photos_taken on photos (taken_at);
create index photos_rating on photos (rating, flag);

create table versions (                         -- named snapshots ("Versions")
  id          integer primary key,
  photo_id    integer not null references photos(id) on delete cascade,
  name        text    not null,
  edit        text    not null,
  created_at  integer not null
);
create index versions_photo on versions (photo_id);

create table presets (
  id          integer primary key,
  name        text    not null unique,
  edit        text    not null,                 -- partial edit: only the keys it sets
  created_at  integer not null,
  updated_at  integer not null
);

create table albums (
  id          integer primary key,
  name        text    not null unique,
  created_at  integer not null,
  updated_at  integer not null
);

create table album_photos (
  album_id    integer not null references albums(id) on delete cascade,
  photo_id    integer not null references photos(id) on delete cascade,
  added_at    integer not null,
  primary key (album_id, photo_id)
);
create index album_photos_photo on album_photos (photo_id);

-- Built-in presets. Each sets only the keys it names, the rest of the photo's edit stays.
insert into presets (name, edit, created_at, updated_at) values
  ('Crisp',      '{"v":1,"light":{"contrast":0.2},"color":{"vibrance":0.2},"effects":{"clarity":0.25},"detail":{"sharpen":0.3}}', 1, 1),
  ('Soft light', '{"v":1,"light":{"contrast":-0.15,"highlights":-0.3,"shadows":0.3},"effects":{"clarity":-0.15}}', 2, 2),
  ('Golden',     '{"v":1,"color":{"temp":0.35,"tint":0.05,"vibrance":0.15}}', 3, 3),
  ('Cool',       '{"v":1,"color":{"temp":-0.3,"saturation":-0.05}}', 4, 4),
  ('Mono',       '{"v":1,"light":{"contrast":0.2},"color":{"saturation":-1,"vibrance":0},"effects":{"clarity":0.15}}', 5, 5),
  ('Faded',      '{"v":1,"light":{"contrast":-0.1},"color":{"saturation":-0.2},"curve":{"rgb":[[0,0.12],[1,0.92]]}}', 6, 6)
