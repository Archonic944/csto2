| Project | Fastest Strategy | Initial Median Time | Naïve Median Time | Speedup Vs. Initial | Naïve Speedup Vs. Initial | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| apache/commons-csv | alloc-front+warm-tail | 12220ms | 11392ms | 16.8% | 6.8% | |
| javaparser/javaparser | pkg-alloc+observed-intra | 15333ms | 15101ms | 23.1% | 1.5% | used javaparser-core-testing |
| apache/commons-text | naive | 18185ms | 15657ms | 13.9% | 13.9% | Second best was jit-front with 13.6%

# Logs

## commons-csv

```
=== CANDIDATE MEASUREMENTS ===
  pkg-alloc-front        runs=4 median=10358ms min=9821ms max=10559ms  GREEN
  naive                  runs=4 median=11392ms min=10253ms max=13185ms  GREEN
  alloc-sort             runs=4 median=10700ms min=10066ms max=10973ms  GREEN
  intra-warmup           runs=4 median=16803ms min=10670ms max=17678ms  GREEN
  pkg-alloc+observed-intra runs=4 median=10534ms min=10016ms max=10603ms  GREEN
  pkg-rt-front           runs=4 median=10258ms min=10207ms max=11302ms  GREEN
  jit-front              runs=4 median=11793ms min=10288ms max=12089ms  GREEN
  alloc-front            runs=4 median=10855ms min=10016ms max=11231ms  GREEN
  warm-tail              runs=4 median=12413ms min=11927ms max=13036ms  GREEN
  initial                runs=4 median=12220ms min=12156ms max=12875ms  GREEN
  alloc-front+warm-tail  runs=4 median=10170ms min=10020ms max=11454ms  GREEN

  pkg-alloc-front        +15.2% vs initial
  naive                  +6.8% vs initial
  alloc-sort             +12.4% vs initial
  intra-warmup           -37.5% vs initial
  pkg-alloc+observed-intra +13.8% vs initial
  pkg-rt-front           +16.1% vs initial
  jit-front              +3.5% vs initial
  alloc-front            +11.2% vs initial
  warm-tail              -1.6% vs initial
  alloc-front+warm-tail  +16.8% vs initial

=> SHIP: alloc-front+warm-tail  (10170ms, 16.8% faster than initial) [green]
```

## javaparser

```
=== CANDIDATE MEASUREMENTS ===
  alloc-front            runs=4 median=12058ms min=11441ms max=12230ms  GREEN
  jit-front              runs=4 median=11827ms min=10404ms max=11914ms  GREEN
  jfr-warmup-front       runs=4 median=14378ms min=12460ms max=15847ms  GREEN
  initial                runs=4 median=15333ms min=13815ms max=15616ms  GREEN
  pkg-alloc+observed-intra runs=4 median=11789ms min=10789ms max=11864ms  GREEN
  alloc-front+warm-tail  runs=4 median=12355ms min=11734ms max=12549ms  GREEN
  alloc-sort             runs=4 median=11800ms min=10823ms max=13076ms  GREEN
  warm-tail              runs=4 median=15578ms min=13432ms max=18343ms  GREEN
  pkg-alloc-front        runs=4 median=12093ms min=10522ms max=12156ms  GREEN
  pkg-rt-front           runs=4 median=12882ms min=12075ms max=12994ms  GREEN
  intra-warmup           runs=4 median=14598ms min=13683ms max=15106ms  GREEN
  naive                  runs=4 median=15101ms min=13288ms max=16152ms  GREEN

  alloc-front            +21.4% vs initial
  jit-front              +22.9% vs initial
  jfr-warmup-front       +6.2% vs initial
  pkg-alloc+observed-intra +23.1% vs initial
  alloc-front+warm-tail  +19.4% vs initial
  alloc-sort             +23.0% vs initial
  warm-tail              -1.6% vs initial
  pkg-alloc-front        +21.1% vs initial
  pkg-rt-front           +16.0% vs initial
  intra-warmup           +4.8% vs initial
  naive                  +1.5% vs initial

=> SHIP: pkg-alloc+observed-intra  (11789ms, 23.1% faster than initial) [green]
```
## commons-text

```
=== CANDIDATE MEASUREMENTS ===
  alloc-front            runs=4 median=15980ms min=15049ms max=16180ms  GREEN
  jit-front              runs=4 median=15719ms min=15057ms max=15884ms  GREEN
  jfr-warmup-front       runs=4 median=17420ms min=17110ms max=18086ms  GREEN
  initial                runs=4 median=18185ms min=17333ms max=18532ms  GREEN
  pkg-alloc+observed-intra runs=4 median=17685ms min=17018ms max=18026ms  GREEN
  alloc-front+warm-tail  runs=4 median=16012ms min=14738ms max=16945ms  GREEN
  alloc-sort             runs=4 median=15772ms min=15065ms max=16024ms  GREEN
  warm-tail              runs=4 median=16225ms min=15235ms max=16836ms  GREEN
  pkg-alloc-front        runs=4 median=18169ms min=17479ms max=18361ms  GREEN
  pkg-rt-front           runs=4 median=18026ms min=17424ms max=18609ms  GREEN
  intra-warmup           runs=4 median=18354ms min=17045ms max=18599ms  GREEN
  naive                  runs=4 median=15657ms min=15566ms max=16307ms  GREEN

  alloc-front            +12.1% vs initial
  jit-front              +13.6% vs initial
  jfr-warmup-front       +4.2% vs initial
  pkg-alloc+observed-intra +2.7% vs initial
  alloc-front+warm-tail  +11.9% vs initial
  alloc-sort             +13.3% vs initial
  warm-tail              +10.8% vs initial
  pkg-alloc-front        +0.1% vs initial
  pkg-rt-front           +0.9% vs initial
  intra-warmup           -0.9% vs initial
  naive                  +13.9% vs initial

=> SHIP: naive  (15657ms, 13.9% faster than initial) [green]
```