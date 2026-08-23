# EPG blocker resolution results

**Date:** 2026-08-23 03:57 UTC
**Plan:** [`epg-blocker-resolution-plan.md`](epg-blocker-resolution-plan.md)
**Policy:** PEG excluded from eligible denom; no invented PEG guides; FAST dual-source **or** WOFTV ≥0.95 exact single-source (user-approved); empty-id only via exact US2 or WOFTV ≥0.95.
**Scorecard:** WOFTV catalogue = **US + CA** union.

## Coverage scorecard

| Category | Before +WOFTV (US+CA) | After +WOFTV | Feed-only | Eligible (no PEG) | Eligible (no permanent*) | Raw 75% need |
|----------|----------------------:|-------------:|----------:|-------------------:|--------------------------:|-------------:|
| Movies | 195/248 (78.6%) | 195/248 (78.6%) | 130/248 (52.4%) | 194/247 (78.5%) | 193/231 (83.5%) | 186 |
| Entertainment | 1379/2137 (64.5%) | 1379/2137 (64.5%) | 778/2137 (36.4%) | 1372/2116 (64.8%) | 1351/1666 (81.1%) | 1603 |
| Local Channels | 38/175 (21.7%) | 38/175 (21.7%) | 37/175 (21.1%) | 38/101 (37.6%) | 37/97 (38.1%) | 131 |

\*Permanent class = PEG / empty `tvg-id` / FAST without dual-or-≥0.95 proof / regional Latin / subchannel-diginet. Covered FAST/empty that passed gates leave this class.

### Verdict vs 75%

| Category | Raw 75% | Eligible (no permanent) 75% |
|----------|---------|-----------------------------|
| Movies | **Met** | **Met** |
| Entertainment | Not met (64.5%) | **Met** |
| Local Channels | Not met (21.7%) | Not met (38.1%) |

## Applied counts

| Lever | Count |
|-------|------:|
| Track 1 empty gated (US2 or WOFTV ≥0.95/dual) | 41 |
| Track 1 **new** name overrides written | 23 |
| Track 1 already overridden (confirmed) | 18 |
| Track 3 FAST accepted (dual / ≥0.95) | 95 |
| Track 3 dual-source | 67 |
| Track 3 WOFTV ≥0.95 single | 28 |
| Track 3 new WOFTV aliases | 0 |
| Name override keys touched | 44 |
| PEG channels classified | 96 |
| Rejects logged | 526 |
| Entertainment empty still permanent | 342 |

Track 3 accepts are mostly **scorecard / permanent-retag** (exact WOFTV name already merges on-device via US+CA); no hex→US2 bridges.

### Track 1 — empty Entertainment shipped

| Name | Target id | Method | Evidence |
|------|-----------|--------|----------|
| US: AMERICAN CRIMES ᴿᴬᵂ | `American.Crimes.us2` | exact_us2_display_name | US2 American.Crimes.us2 display=American Crimes |
| US: BEYOND PARANORMAL ᴿᴬᵂ | `BEYONDPARANORMAL.woftv` | woftv_exact_095 | key=beyond paranormal; score=0.96; platforms=plex; sample=Ghost Dimension |
| US: BIG 12 STUDIOS ᴿᴬᵂ | `99951359` | woftv_dual_exact | key=big 12 studios; score=0.96; platforms=roku; samsung tv plus; tubi; sample=Arizona State vs. Iowa State | 2024 Big 12 |
| US: BONDI RESCUE ᴿᴬᵂ | `BONDIRESCUE.woftv` | woftv_dual_exact | key=bondi rescue; score=0.95; platforms=plex; pluto tv; samsung tv plus; sample=Bondi Rescue |
| US: CANELA CLASICOS ᴿᴬᵂ | `CanelaClasicos.us` | woftv_exact_095 | key=canela clasicos; score=0.95; platforms=xumo play; sample=Jalisco canta en Sevilla |
| US: CINE EN ESPAÑOL ᴿᴬᵂ | `CINEENESPAOL.woftv` | woftv_dual_exact | key=cine en espa ol; score=0.96; platforms=plex; pluto tv; sample=Ladrón que roba a ladrón |
| US: CIRQUE DU SOLEIL ᴿᴬᵂ | `CIRQUEDUSOLEIL.woftv` | woftv_dual_exact | key=cirque du soleil; score=0.96; platforms=plex; roku; sample="O", Saltimbanco & 'Twas the Night Before… |
| US: CONAN O'BRIEN TV ᴿᴬᵂ | `USBD120001614` | woftv_exact_095 | key=conan o brien; score=0.96; platforms=samsung tv plus; sample=Conan O'Brien |
| US: DANCE MOMS ᴿᴬᵂ | `USBC3900021HS` | woftv_dual_exact | key=dance moms; score=0.94; platforms=plex; pluto tv; roku; sample=Dance Moms |
| US: DOG WHISPERER WITH CESAR MILLAN ᴿᴬᵂ | `DOGWHISPERERWITHCESARMILLAN.woftv` | woftv_dual_exact | key=dog whisperer with cesar millan; score=0.98; platforms=pluto tv; roku; sample=Dog Whisperer |
| US: HORROR MACHINE ᴿᴬᵂ | `Horror.Machine.us2` | exact_us2_display_name | US2 Horror.Machine.us2 display=Horror Machine |
| US: JTV JEWELRY LOVE ᴿᴬᵂ | `JTVJEWELRYLOVE.woftv` | woftv_exact_095 | key=jtv jewelry love; score=0.96; platforms=xumo play; sample=Off Park Jewelry Collection |
| US: NASCAR ᴿᴬᵂ | `NASCAR.Channel.us2` | exact_us2_display_name | US2 NASCAR.Channel.us2 display=NASCAR Channel |
| US: NATURALEZA SALVAJE ᴿᴬᵂ | `NATURALEZASALVAJE.woftv` | woftv_dual_exact | key=naturaleza salvaje; score=0.96; platforms=plex; roku; sample=Diarios de vida silvestre de Kenia |
| US: PLL NETWORK ᴿᴬᵂ | `PLLNETWORK.woftv` | woftv_exact_095 | key=pll network; score=0.95; platforms=plex; sample=Mitchell Pehlke Lacrosse Show |
| US: POWER RANGERS ᴿᴬᵂ | `POWERRANGERS.woftv` | woftv_dual_exact | key=power rangers; score=0.95; platforms=plex; samsung tv plus; xumo play; sample=Power Rangers: Jungle Fury |
| US: PROJECT RUNWAY ᴿᴬᵂ | `USBB3500004RC` | woftv_dual_exact | key=project runway; score=0.96; platforms=plex; pluto tv; roku; sample=Project Runway All Stars |
| US: REAL CRIME ᴿᴬᵂ | `REALCRIME.woftv` | woftv_dual_exact | key=real crime; score=0.94; platforms=plex; roku; sample=Mafia's Greatest Hits |
| US: REAL CRIME UNCOVERED ᴿᴬᵂ | `REALCRIMEUNCOVERED.woftv` | woftv_exact_095 | key=real crime uncovered; score=0.97; platforms=plex; sample=Fatal Crimes |
| US: SUPERMARKET SWEEP ᴿᴬᵂ | `SUPERMARKETSWEEP.woftv` | woftv_dual_exact | key=supermarket sweep; score=0.96; platforms=plex; pluto tv; roku; sample=Supermarket Sweep |
| US: SWERVE COMBAT ᴿᴬᵂ | `SWERVECOMBAT.woftv` | woftv_dual_exact | key=swerve combat; score=0.95; platforms=plex; roku; xumo play; sample=BKB Bare Knuckle 56: Ortiz vs Salcido |
| US: TASTEMADE HOME ᴿᴬᵂ | `Tastemade.Home.us2` | exact_us2_display_name | US2 Tastemade.Home.us2 display=Tastemade Home |
| US: TRAVEL ESCAPES ᴿᴬᵂ | `TRAVELESCAPES.woftv` | woftv_exact_095 | key=travel escapes; score=0.96; platforms=plex; sample=Passport Heavy |
| US: TV BLOSSOM ᴿᴬᵂ | `Blossom.TV.us2` | exact_us2_display_name | US2 Blossom.TV.us2 display=Blossom TV |
| US: WILD WEST TV ᴿᴬᵂ | `US4500003B0` | woftv_dual_exact | key=wild west; score=0.95; platforms=plex; samsung tv plus; xumo play; sample=Return to Lonesome Dove |
| UK: 21 JUMP STREET ᴿᴬᵂ | `21JumpStreet.uk` | woftv_dual_exact | key=21 jump street; score=0.96; platforms=plex; samsung tv plus; sample=21 Jump Street |
| UK: COME DINE WITH ME ᴿᴬᵂ | `COMEDINEWITHME.woftv` | woftv_dual_exact | key=come dine with me; score=0.96; platforms=plex; pluto tv; xumo play; sample=COME DINE WITH ME |
| UK: COSMIC FRONTIERS ᴿᴬᵂ | `CosmicFrontiers.uk` | woftv_dual_exact | key=cosmic frontiers; score=0.96; platforms=plex; xumo play; sample=Shockwave |
| UK: CRIME BEAT TV ᴿᴬᵂ | `CrimeBeat.uk` | woftv_exact_095 | key=crime beat; score=0.95; platforms=plex; sample=Hunter |
| UK: DUDE PERFECT ᴿᴬᵂ | `DUDEPERFECT.woftv` | woftv_exact_095 | key=dude perfect; score=0.95; platforms=plex; sample=Dude Perfect Overtime |
| UK: EVOLUTION EARTH ᴿᴬᵂ | `EVOLUTIONEARTH.woftv` | woftv_dual_exact | key=evolution earth; score=0.96; platforms=plex; samsung tv plus; sample=Evolution Earth |
| UK: FLIPPING NATION ᴿᴬᵂ | `FlippingNation.uk` | woftv_dual_exact | key=flipping nation; score=0.96; platforms=plex; samsung tv plus; sample=Flip This House |
| UK: GREAT BRITISH MENU ᴿᴬᵂ | `GreatBritishMenu.uk` | woftv_dual_exact | key=great british menu; score=0.96; platforms=plex; xumo play; sample=Great British Menu |
| UK: HARDCORE PAWN ᴿᴬᵂ | `HardcorePawn.uk` | woftv_dual_exact | key=hardcore pawn; score=0.95; platforms=plex; pluto tv; sample=Hardcore Pawn |
| UK: HIGHWAY THRU HELL ᴿᴬᵂ | `HighwayThruHell.uk` | woftv_dual_exact | key=highway thru hell; score=0.96; platforms=plex; roku; xumo play; sample=Highway Thru Hell |
| UK: INTERVENTION ᴿᴬᵂ | `INTERVENTION.woftv` | woftv_dual_exact | key=intervention; score=0.95; platforms=plex; samsung tv plus; sample=Intervention |
| UK: LOVE AFTER LOCKUP ᴿᴬᵂ | `LOVEAFTERLOCKUP.woftv` | woftv_exact_095 | key=love after lockup; score=0.96; platforms=roku; sample=Love After Lockup |
| UK: ROMCOM K-DRAMA ᴿᴬᵂ | `RomcomKDrama.uk` | woftv_exact_095 | key=romcom k drama; score=0.96; platforms=plex; sample=My ID is Gangnam Beauty |
| UK: WHOSE LINE IS IT ANYWAY? ᴿᴬᵂ | `WHOSELINEISITANYWAY.woftv` | woftv_exact_095 | key=whose line is it anyway; score=0.97; platforms=roku; sample=Whose Line Is It Anyway? |
| UK: WICKED TUNA ᴿᴬᵂ | `WickedTuna.uk` | woftv_dual_exact | key=wicked tuna; score=0.95; platforms=pluto tv; roku; samsung tv plus; sample=Wicked Tuna |
| INT: METV TOONS ᴿᴬᵂ | `MeTV.Toons.us2` | exact_us2_display_name | US2 MeTV.Toons.us2 display=MeTV Toons |

### Track 3 — FAST shipped / accepted

| tvg-id | Name | Method | Action | Evidence |
|--------|------|--------|--------|----------|
| `99951256` | US: ALFRED HITCHCOCK PRESENTS ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=alfred hitchcock presents; score=0.97; platforms=roku; samsung tv plus; xumo play; sample=Alfred |
| `692057` | US: ALIEN NATION BY DUST ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=alien nation by dust; score=0.97; platforms=plex; roku; samsung tv plus; sample=Dark Coast: Hunt |
| `691cf0410a78a9ce61ad2121` | US: AMERICA'S FUNNIEST HOME VIDEOS ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=america s funniest home videos; score=0.98; platforms=plex; pluto tv; roku; sample=America's Fun |
| `99951318` | US: AMERICAN NINJA WARRIOR ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=american ninja warrior; score=0.97; platforms=roku; samsung tv plus; xumo play; sample=American  |
| `66c638726838ee00085ac20d` | US: ANDROMEDA ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=andromeda; score=0.94; platforms=plex; pluto tv; sample=Andromeda |
| `682059` | US: ANGER MANAGEMENT CHANNEL ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=anger management; score=0.93; platforms=plex; pluto tv; roku; sample=Anger Management |
| `682057` | US: ARE WE THERE YET? ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=are we there yet; score=0.96; platforms=plex; pluto tv; roku; sample=Are We There Yet? |
| `99951156` | US: AT HOME WITH FAMILY HANDYMAN ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=at home with family handyman; score=0.98; platforms=plex; roku; samsung tv plus; sample=Income P |
| `654932fa4d6d8f00084c4723` | US: BAD GIRLS CLUB ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=bad girls club; score=0.96; platforms=pluto tv; roku; samsung tv plus; sample=Bad Girls Club |
| `654932fa4d6d8f00084c4723` | US: BAD GIRLS CLUB ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=bad girls club; score=0.96; platforms=pluto tv; roku; samsung tv plus; sample=Bad Girls Club |
| `60f760bbdf090700075d7bfe` | US: BEST OF DR. PHIL ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=best of dr phil; score=0.96; platforms=pluto tv; roku; sample=Dr. Phil |
| `5ca670f6593a5d78f0e85aed` | US: BET PLUTO TV ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=bet pluto; score=0.95; platforms=pluto tv; roku; samsung tv plus; sample=The Game |
| `99951359` | US: BIG 12 STUDIOS ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=big 12 studios; score=0.96; platforms=roku; samsung tv plus; tubi; sample=Arizona State vs. Iowa |
| `6549310a53fc97000838fcc9` | US: BRAVO VAULT ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=bravo vault; score=0.95; platforms=plex; pluto tv; roku; sample=Southern Charm |
| `6549310a53fc97000838fcc9` | US: BRAVO VAULT ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=bravo vault; score=0.95; platforms=plex; pluto tv; roku; sample=Southern Charm |
| `5cf96b1c4f1ca3f0629f4bf0` | US: CINE EN ESPAÑOL ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=cine en espa ol; score=0.96; platforms=plex; pluto tv; sample=Ladrón que roba a ladrón |
| `5421f71da6af422839419cb3` | US: CNN HEADLINES ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=cnn headlines; score=0.95; platforms=plex; pluto tv; roku; sample=American Pulse |
| `634ee8f95aa9870007248333` | US: CONFESS BY NOSEY ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=confess by nosey; score=0.96; platforms=plex; pluto tv; roku; sample=The Maury Show |
| `99951210` | US: CRAFTSYTV ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=craftsytv; score=0.94; platforms=plex; samsung tv plus; xumo play; sample=Cook & Create |
| `65b14aae0cb1a100087a216e` | US: DAZN RINGSIDE ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=dazn ringside; score=0.95; platforms=plex; pluto tv; tubi; sample=LIVE Weigh In |
| `65b14aae0cb1a100087a216e` | US: DAZN RINGSIDE ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=dazn ringside; score=0.95; platforms=plex; pluto tv; tubi; sample=LIVE Weigh In |
| `664fd48894d5580008e45e7c` | US: DOG WHISPERER WITH CESAR MILLAN ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=dog whisperer with cesar millan; score=0.98; platforms=pluto tv; roku; sample=Dog Whisperer |
| `99951465` | US: E! KEEPING UP ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=e keeping up; score=0.95; platforms=plex; roku; samsung tv plus; sample=Flip It Like Disick |
| `672e4d915534bb0008c50168` | US: FAMILY FEUD ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=family feud; score=0.95; platforms=plex; pluto tv; roku; sample=Family Feud |
| `672e4d915534bb0008c50168` | US: FAMILY FEUD ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=family feud; score=0.95; platforms=plex; pluto tv; roku; sample=Family Feud |
| `64c2222fb0cf5c0008288c4f` | US: FAMILY FEUD CLASSIC ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=family feud classic; score=0.97; platforms=plex; pluto tv; samsung tv plus; sample=Family Feud |
| `64c2222fb0cf5c0008288c4f` | US: FAMILY FEUD CLASSIC ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=family feud classic; score=0.97; platforms=plex; pluto tv; samsung tv plus; sample=Family Feud |
| `650b68bc2ce8e40008ac9c14` | US: FANDUEL TV EXTRA ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=fanduel extra; score=0.96; platforms=roku; tubi; sample=PDC Darts Classics |
| `650b68bc2ce8e40008ac9c14` | US: FANDUEL TV EXTRA ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=fanduel extra; score=0.96; platforms=roku; tubi; sample=PDC Darts Classics |
| `670602` | US: FEAR FACTOR ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=fear factor; score=0.95; platforms=plex; roku; samsung tv plus; sample=Fear Factor USA |
| `64e561a4354251000823a0e0` | US: GHOST HUNTERS ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=ghost hunters; score=0.95; platforms=plex; pluto tv; roku; sample=Ghost Hunters |
| `677011` | US: GORDON RAMSAY ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=gordon ramsay; score=0.95; platforms=tubi; sample=KITCHEN NIGHTMARES (2023) |
| `692051` | US: HORROR BY ALTER ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=horror by alter; score=0.96; platforms=plex; samsung tv plus; tubi; sample=The Convent |
| `99951344` | US: JOEL OSTEEN NETWORK ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=joel osteen network; score=0.97; platforms=roku; xumo play; sample=Joel Osteen Weekly |
| `99951257` | US: LEAVE IT TO BEAVER ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=leave it to beaver; score=0.96; platforms=roku; xumo play; sample=Leave It to Beaver |
| `6887a4e01067b66fa16966ec` | US: LIVE PD PRESENTS ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=pd presents; score=0.96; platforms=plex; pluto tv; roku; sample=Live PD: Police Patrol |
| `6549322e53fc97000838febc` | US: MILLION DOLLAR LISTING VAULT ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=million dollar listing vault; score=0.98; platforms=plex; pluto tv; roku; sample=Million Dollar  |
| `6549322e53fc97000838febc` | US: MILLION DOLLAR LISTING VAULT ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=million dollar listing vault; score=0.98; platforms=plex; pluto tv; roku; sample=Million Dollar  |
| `5f77977bd924d80007eee60c` | US: MISSION IMPOSSIBLE ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=mission impossible; score=0.96; platforms=pluto tv; sample=Mission: Impossible |
| `6549337183595c000815ad05` | US: MURDER SHE WROTE ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=murder she wrote; score=0.96; platforms=pluto tv; roku; samsung tv plus; sample=Murder, She Wrot |
| `6549337183595c000815ad05` | US: MURDER SHE WROTE ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=murder she wrote; score=0.96; platforms=pluto tv; roku; samsung tv plus; sample=Murder, She Wrot |
| `563a970aa1a1f7fe7c9daad7` | US: PLUTO TV SCIENCE ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=pluto science; score=0.96; platforms=pluto tv; sample=Ancient Engineering |
| `99951301` | US: PORTLANDIA ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=portlandia; score=0.94; platforms=plex; samsung tv plus; xumo play; sample=Portlandia |
| `6549316d2c1d330008631496` | US: REAL HOUSEWIVES VAULT ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=real housewives vault; score=0.97; platforms=plex; pluto tv; roku; sample=The Real Housewives of |
| `6549316d2c1d330008631496` | US: REAL HOUSEWIVES VAULT ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=real housewives vault; score=0.97; platforms=plex; pluto tv; roku; sample=The Real Housewives of |
| `400000064` | US: SILENT WITNESS AND NEW TRICKS ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=silent witness and new tricks; score=0.93; platforms=roku; tubi; sample=New Tricks |
| `649ddbfb6f29ec000874ca9e` | US: SUPERMARKET SWEEP ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=supermarket sweep; score=0.96; platforms=plex; pluto tv; roku; sample=Supermarket Sweep |
| `400000112` | US: TODO CINE ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=todo cine; score=0.94; platforms=plex; roku; tubi; sample=Las zetas |
| `65a7b04e7bdc8d0008488307` | US: TRUE CRIME NOW ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=true crime now; score=0.96; platforms=plex; pluto tv; roku; sample=Coffee and Crime Time |
| `5b4e96a0423e067bd6df6901` | US: UNSOLVED MYSTERIES ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=unsolved mysteries; score=0.96; platforms=plex; pluto tv; roku; sample=Unsolved Mysteries with R |
| `686c2677ed17cf4c900a496d` | US: WICKED TUNA ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=wicked tuna; score=0.95; platforms=pluto tv; roku; samsung tv plus; sample=Wicked Tuna |
| `670603` | US: WIPEOUT XTRA ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=wipeout xtra; score=0.95; platforms=samsung tv plus; tubi; sample=Total Wipeout |
| `691cf0410a78a9ce61ad2121` | UK: AMERICA'S FUNNIEST HOME VIDEOS ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=america s funniest home videos; score=0.98; platforms=plex; pluto tv; roku; sample=America's Fun |
| `5ca670f6593a5d78f0e85aed` | UK: BET PLUTO TV ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=bet pluto; score=0.95; platforms=pluto tv; roku; samsung tv plus; sample=The Game |
| `66166989635c7500084105a2` | UK: BONDI RESCUE ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=bondi rescue; score=0.95; platforms=plex; pluto tv; samsung tv plus; sample=Bondi Rescue |
| `5421f71da6af422839419cb3` | UK: CNN HEADLINES ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=cnn headlines; score=0.95; platforms=plex; pluto tv; roku; sample=American Pulse |
| `634ee8f95aa9870007248333` | UK: CONFESS BY NOSEY ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=confess by nosey; score=0.96; platforms=plex; pluto tv; roku; sample=The Maury Show |
| `65b14aae0cb1a100087a216e` | UK: DAZN RINGSIDE (684P) ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=dazn ringside; score=0.95; platforms=plex; pluto tv; tubi; sample=LIVE Weigh In |
| `672e4d915534bb0008c50168` | UK: FAMILY FEUD ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=family feud; score=0.95; platforms=plex; pluto tv; roku; sample=Family Feud |
| `6852811933a52de120f8b89d` | UK: MAYDAY AIR DISASTER ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=mayday air disaster; score=0.96; platforms=plex; pluto tv; roku; sample=Mayday: Air Disaster |
| `6852811933a52de120f8b89d` | UK: MAYDAY AIR DISASTER ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=mayday air disaster; score=0.96; platforms=plex; pluto tv; roku; sample=Mayday: Air Disaster |
| `5f77977bd924d80007eee60c` | UK: MISSION IMPOSSIBLE ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=mission impossible; score=0.96; platforms=pluto tv; sample=Mission: Impossible |
| `6540ff2d770cf1000866b90a` | UK: MODERN MARVELS ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=modern marvels; score=0.96; platforms=plex; pluto tv; roku; sample=Modern Marvels |
| `671645c4529ac900080c9a0b` | UK: MODUS SUPER SERIES DARTS ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=modus super series darts; score=0.97; platforms=pluto tv; sample=In His Own Words: Andy Davidson |
| `66d722d31878f200081c2c64` | UK: MOST HAUNTED ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=most haunted; score=0.95; platforms=plex; sample=Most Haunted |
| `561d7d484dc7c8770484914a` | UK: PLUTO TV ACTION ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=pluto action; score=0.96; platforms=pluto tv; sample=Mission: Impossible - Rogue Nation |
| `5f31fd1b4c510e00071c3103` | UK: PLUTO TV CRIME DRAMA ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=pluto crime drama; score=0.97; platforms=pluto tv; sample=CSI: Vegas |
| `5c665db3e6c01b72c4977bc2` | UK: PLUTO TV CULT FILMS ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=pluto cult films; score=0.97; platforms=pluto tv; sample=Bloodsport |
| `5b4e92e4694c027be6ecece1` | UK: PLUTO TV DRAMA ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=pluto drama; score=0.96; platforms=pluto tv; sample=The Illusionist |
| `569546031a619b8f07ce6e25` | UK: PLUTO TV HORROR ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=pluto horror; score=0.96; platforms=pluto tv; sample=C.H.U.D. |
| `5adf96e3e738977e2c31cb04` | UK: PLUTO TV PARANORMAL ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=pluto paranormal; score=0.97; platforms=pluto tv; sample=Beyond Belief: Fact or Fiction |
| `5d8bf0b06d2d855ee15115e3` | UK: PLUTO TV REALITY ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=pluto reality; score=0.96; platforms=pluto tv; sample=Farmer Wants a Wife |
| `5b4fc274694c027be6ed3eea` | UK: PLUTO TV SCI-FI ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=pluto sci fi; score=0.96; platforms=pluto tv; sample=Fringe |
| `563a970aa1a1f7fe7c9daad7` | UK: PLUTO TV SCIENCE ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=pluto science; score=0.96; platforms=pluto tv; sample=Ancient Engineering |
| `639b4f75d3d35c0007d37b30` | UK: PLUTO TV SNOOKER 900 ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=pluto snooker 900; score=0.97; platforms=pluto tv; sample=The Creator Clash |
| `5b4e69e08291147bd04a9fd7` | UK: PLUTO TV THRILLERS ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=pluto thrillers; score=0.96; platforms=pluto tv; sample=The Net |
| `5b4e94282d4ec87bdcbb87cd` | UK: PLUTO TV WESTERNS ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=pluto westerns; score=0.96; platforms=pluto tv; samsung tv plus; sample=The Rifleman |
| `65367e914f123d000877d021` | UK: RIVER MONSTERS ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=river monsters; score=0.96; platforms=plex; pluto tv; sample=River Monsters |
| `65367e914f123d000877d021` | UK: RIVER MONSTERS ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=river monsters; score=0.96; platforms=plex; pluto tv; sample=River Monsters |
| `6127e12ed140e900077e7b6f` | UK: TEEN MOM ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=teen mom; score=0.93; platforms=pluto tv; roku; samsung tv plus; sample=Teen Mom 2 |
| `400000065` | UK: TOP GEAR ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=top gear; score=0.93; platforms=plex; pluto tv; roku; sample=Top Gear UK |
| `5d0c16d686454ead733d08f8` | UK: TOTALLY TURTLES ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=totally turtles; score=0.96; platforms=pluto tv; sample=Teenage Mutant Ninja Turtles |
| `68504098f212bedacf63a7e1` | UK: TOUCHED BY AN ANGEL ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=touched by an angel; score=0.97; platforms=pluto tv; sample=Touched By An Angel |
| `686c2677ed17cf4c900a496d` | UK: WICKED TUNA ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=wicked tuna; score=0.95; platforms=pluto tv; roku; samsung tv plus; sample=Wicked Tuna |
| `677fb0b33a225c2d0d8a17be` | UK: XENA: WARRIOR PRINCESS ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=xena warrior princess; score=0.96; platforms=xumo play; sample=Xena: Warrior Princess |
| `561d7d484dc7c8770484914a` | US: PLUTO TV ACTION ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=pluto action; score=0.96; platforms=pluto tv; sample=Mission: Impossible - Rogue Nation |
| `5f31fd1b4c510e00071c3103` | US: PLUTO TV CRIME DRAMA ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=pluto crime drama; score=0.97; platforms=pluto tv; sample=CSI: Vegas |
| `5c665db3e6c01b72c4977bc2` | US: PLUTO TV CULT FILMS ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=pluto cult films; score=0.97; platforms=pluto tv; sample=Bloodsport |
| `5b4e92e4694c027be6ecece1` | US: PLUTO TV DRAMA ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=pluto drama; score=0.96; platforms=pluto tv; sample=The Illusionist |
| `569546031a619b8f07ce6e25` | US: PLUTO TV HORROR ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=pluto horror; score=0.96; platforms=pluto tv; sample=C.H.U.D. |
| `5b4fc274694c027be6ed3eea` | US: PLUTO TV SCI-FI ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=pluto sci fi; score=0.96; platforms=pluto tv; sample=Fringe |
| `5b4e69e08291147bd04a9fd7` | US: PLUTO TV THRILLERS ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=pluto thrillers; score=0.96; platforms=pluto tv; sample=The Net |
| `5b4e94282d4ec87bdcbb87cd` | US: PLUTO TV WESTERNS ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=pluto westerns; score=0.96; platforms=pluto tv; samsung tv plus; sample=The Rifleman |
| `680705` | US: WANTED: DEAD OR ALIVE ᴿᴬᵂ | fast_woftv_dual | covered_woftv_exact | key=wanted dead or alive; score=0.97; platforms=plex; samsung tv plus; tubi; sample=Wanted: Dead or  |
| `62ea3d24b8e02600071fa296` | UK: PLUTO TV COMEDY MOVIES ᴿᴬᵂ | fast_woftv_exact_095 | covered_woftv_exact | key=pluto comedy movies; score=0.97; platforms=pluto tv; sample=Dotty & Soul |

### Track 2 — PEG policy

- **PEG classified:** {'Entertainment': 21, 'Movies': 1, 'Local Channels': 74} (playlist total **96**)
- **List:** `reports/residuals/peg-channels.csv`
- **Asset:** `app/src/main/assets/epg_peg_channels.json`
- **UI Community badge:** not shipped (no existing playlist badge hook reused); policy/list only — follow-up if product wants `Community` group/tag.
- **Bridges for PEG:** **0** (forbidden).

## Niche permanent uncovered (Entertainment empty)

- Remaining Entertainment empty-id permanent: **342** (religious, niche FAST, geo sports, Latino without feed, webcams, etc.).
- Documented in `reports/residuals/permanent-uncovered.csv` (`empty_tvg_id`).

## Reject samples (gates)

- `no_us2_no_woftv_audit`: 295
- `no_woftv_audit`: 46
- `reject_ambiguous_short`: 32
- `short_ambiguous`: 9
- `gate_fail_score_0.81_dual_True`: 7
- `woftv_score_0.00_below_gate`: 6
- `woftv_score_0.56_below_gate`: 5
- `woftv_score_0.57_below_gate`: 4
- `woftv_score_0.52_below_gate`: 4
- `woftv_score_0.75_below_gate`: 4
- `woftv_score_0.70_below_gate`: 4
- `reject_short_woftv`: 4
- `woftv_score_0.62_below_gate`: 3
- `woftv_score_0.58_below_gate`: 3
- `woftv_score_0.81_below_gate`: 3

## Artifacts

| Path | Purpose |
|------|---------|
| `reports/epg-blocker-resolution-results.md` | This report |
| `reports/residuals/applied-candidates.csv` | Applied batch |
| `reports/residuals/blocker-rejects.csv` | Reject log |
| `reports/residuals/peg-channels.csv` | PEG list |
| `reports/residuals/permanent-uncovered.csv` | Permanent tags |
| `reports/residuals/residual-inventory.csv` | Post-pass residuals |
| `app/src/main/assets/epg_name_overrides.json` | Empty-id → stable/feed ids |
| `app/src/main/assets/epg_peg_channels.json` | PEG policy list |

## Now-playing proof (host)

| Channel | WOFTV source | Sample title | Confidence |
|---------|--------------|--------------|------------|
| UK: HARDCORE PAWN → `HardcorePawn.uk` | woftv-ca.json | Hardcore Pawn | high |
| UK: EVOLUTION EARTH → `EVOLUTIONEARTH.woftv` | woftv-ca.json | Evolution Earth | high |
| UK: MOST HAUNTED | woftv-ca.json | Most Haunted | high |
| UK: RIVER MONSTERS | woftv-ca.json | River Monsters | high |
| UK: MODUS SUPER SERIES DARTS | woftv-ca.json | In His Own Words: Andy Davidson | high |
| UK: PLUTO TV SNOOKER 900 | woftv-ca.json | The Creator Clash | high |
| UK: ARTHUR / TAXI / CATFISH | — | **rejected** ambiguous short | — |

## Compile / tests

- `compileDebugKotlin`: **SUCCESS**
- `testDebugUnitTest` `--tests 'com.thothassistant.stepdaddy.gateway.epg.*'`: **SUCCESS**

## Notes

1. Host scorecard now uses **WOFTV US+CA** (prior residual-75 used US-only), which correctly credits UK FAST titles in `epg-ca.json`.
2. Empty-id WOFTV hits need name overrides so `EpgManager` includes a tvg-id; `mergeGaps` fills by display-name → catalogue key.
3. Track 3 FAST accepts needed **no new aliases** when display-norm already equals the catalogue key.
4. PEG: policy list only — **no** invented guides; Community UI badge deferred.
5. Entertainment **raw** 75% still out of reach (~64.5%); **eligible-without-permanent met at 81.1%**.

