# StepDaddy Gateway 3.0.24

## Added
- VOD priority shelves — Movies and series catalogs fill priority Nextbox/vsembed shelves first (Popular, Trending, Latest, etc.) then backfill remaining tier caps. Separate `shelfCategories` from Cinemeta `genre`; multi-shelf rows let the same title appear in multiple Xtream categories.
- Tiered VOD caps — Fire Stick 150/150, Onn 250/250, full-RAM devices 300/300 via `VodCatalogLimits`.
- Debug/release coexistence guard — `GatewayPackageGuard` stops the sibling gateway service on startup and logs port-conflict hints when bind fails.

## Fixed
- Cinemeta shelf overwrite — Cinemeta enrichment no longer replaces Nextbox shelf labels on movie rows.
- Debug + release port 3000 conflict — Release stops debug service; debug stops release service so only one binds port 3000.
