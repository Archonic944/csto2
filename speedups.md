| Project | Fastest Strategy | Initial Median Time | Naïve Median Time | Speedup Vs. Initial | Naïve Speedup Vs. Initial | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| apache/commons-csv | alloc-front+warm-tail | 12220ms | 11392ms | 16.8% | 6.8% | |
| javaparser/javaparser | pkg-rt-front | 13527ms | 12597ms | 23.3% | 6.9% | used javaparser-core-testing; alloc-front and pkg-alloc-front are the same speedup |
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
  alloc-front            runs=4 median=10527ms min=10151ms max=10552ms  GREEN
  jit-front              runs=4 median=10865ms min=9995ms max=11768ms  GREEN
  jfr-warmup-front       runs=4 median=12724ms min=11661ms max=12792ms  GREEN
  initial                runs=4 median=13527ms min=12879ms max=13694ms  GREEN
  pkg-alloc+observed-intra runs=4 median=10672ms min=9651ms max=11367ms  GREEN
  alloc-front+warm-tail  runs=4 median=10714ms min=9943ms max=12762ms  GREEN
  alloc-sort             runs=4 median=10545ms min=9805ms max=10996ms  GREEN
  warm-tail              runs=4 median=13625ms min=13282ms max=13729ms  GREEN
  pkg-alloc-front        runs=4 median=10515ms min=10058ms max=10691ms  GREEN
  pkg-rt-front           runs=4 median=10373ms min=10135ms max=11171ms  GREEN
  intra-warmup           runs=4 median=13656ms min=13009ms max=14477ms  GREEN
  naive                  runs=4 median=12597ms min=12028ms max=13235ms  GREEN

  alloc-front            +22.2% vs initial
  jit-front              +19.7% vs initial
  jfr-warmup-front       +5.9% vs initial
  pkg-alloc+observed-intra +21.1% vs initial
  alloc-front+warm-tail  +20.8% vs initial
  alloc-sort             +22.0% vs initial
  warm-tail              -0.7% vs initial
  pkg-alloc-front        +22.3% vs initial
  pkg-rt-front           +23.3% vs initial
  intra-warmup           -1.0% vs initial
  naive                  +6.9% vs initial

=> SHIP: pkg-rt-front  (10373ms, 23.3% faster than initial) [green]
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

