-- Schema v2. Run after schema.sql on a new install, or on its own on a v1 database. One statement per line ending in ";".
-- taken_at is never filled in, so its index only cost time on every write.
drop index if exists photos_taken;
-- The v1 check let 2.5 through as a rating: whole numbers only (a table rebuild would also cascade-delete versions and albums).
create trigger photos_rating_insert before insert on photos when typeof(new.rating) != 'integer' begin select raise(abort, 'rating must be a whole number'); end;
create trigger photos_rating_update before update of rating on photos when typeof(new.rating) != 'integer' begin select raise(abort, 'rating must be a whole number'); end;
