# Offline growth references

Source: WHO Child Growth Standards, expanded daily LMS tables for boys and girls, ages 0–1856 days. These are reference charts, not diagnoses or feeding recommendations. No network access occurs in the app.

- [Weight for age](https://www.who.int/tools/child-growth-standards/standards/weight-for-age)
- [Length/height for age](https://www.who.int/toolkits/child-growth-standards/standards/length-height-for-age)
- [Head circumference for age](https://www.who.int/tools/child-growth-standards/standards/head-circumference-for-age)

Official XLSX names: `wfa-{boys,girls}-zscore-expanded-tables.xlsx`, `lhfa-{boys,girls}-zscore-expanded-tables.xlsx`, `hcfa-{boys,girls}-zscore-expanded-tables.xlsx`. Downloaded from WHO's `cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/` tree. Weight and head circumference use `expanded-tables`; length/height uses `expandable-tables`.

`scripts/convert-who.ps1` reads the first four numeric columns (day, L, M, S) without rounding from the official workbooks in ignored `.tools/who`. Generated CSVs in `app/src/main/assets/who` contain 1,857 daily observations each. No smoothing or invented reference values are used. Units are kilograms and centimeters. Length is used before 24 months and height thereafter; the reference curve is broken at that transition.

For nonzero L, z = ((measurement / M)^L − 1) / (L × S). For zero L, z = ln(measurement / M) / S. The app converts z to a standard-normal cumulative percentile and draws the 2nd, 5th, 10th, 25th, 50th, 75th, 90th, 95th and 98th reference curves. Birth date or the user-supplied adjusted birth date determines age; no percentile is shown outside the table or without the required inputs.

WHO is the source of the reference data and does not endorse BabyWise. Charts intentionally omit clinical classifications or recommendations.
