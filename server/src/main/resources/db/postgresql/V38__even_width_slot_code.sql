-- A cell's code ran the level and the column together as one number, so a person had to know the
-- convention to see where one stopped. It now reads as the rack, the column, and the level, with a
-- separator between the two: R-A 20/08 is column 20, level 8.
--
-- The code was never entered, only derived from the rack and the cell's place in it, so it is
-- derived again here rather than patched. Nothing parses it -- it is a label -- so rewriting it
-- changes what people read and nothing else.
--
-- lpad is deliberately not used: it truncates a string already longer than the width, and a rack
-- may be 200 columns wide. Padding is a minimum, never a limit.
UPDATE equipment.slot s
   SET code = r.code || ' '
            || CASE WHEN s.col   < 10 THEN '0' ELSE '' END || s.col::text   || '/'
            || CASE WHEN s.level < 10 THEN '0' ELSE '' END || s.level::text
  FROM equipment.rack r
 WHERE r.id = s.rack_id
   AND s.code IS DISTINCT FROM r.code || ' '
            || CASE WHEN s.col   < 10 THEN '0' ELSE '' END || s.col::text   || '/'
            || CASE WHEN s.level < 10 THEN '0' ELSE '' END || s.level::text;
