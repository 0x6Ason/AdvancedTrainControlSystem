# Arrival estimates

Every time a board, the boss bar, a hologram, SignLink or the website says *3 min*, it is the
same estimator speaking. This page says where the number comes from, how to measure how good it
is on your railway, and what to do when a stop lies.

## Where the number comes from

ATCS learns, it is not told. Nothing in `network.yml` says how long a stretch takes.

- **Travel times per stretch of track.** When a train leaves one platform and reaches the next,
  the time is recorded for that pair of platforms and smoothed into the value kept for it
  (`timing.smoothing`, 0.3 by default: a third of the way towards each new run, so one slow run
  moves the number a little and a sustained change moves it within a few runs). Services on the
  same track share what any of them learned, with each service's own runs preferred once it has
  some; a new express is accurate from its first trip.
- **Stop lengths per stop**, learned the same way, with the dwell an `atcs station` sign sets
  known exactly rather than learned.
- **Where the train is.** The remaining time on the current stretch comes from the train's
  position along the recorded track, not only from the clock, so a train that was slow or held
  does not sit on *Arriving* while the number says otherwise.
- **What ATCS itself will do.** A hold the station rules will impose (a headway gap, a block
  that is not clear, even spacing, a timetable slot, junction priority) is predicted from the
  rule and added, instead of learned as if it were travel time. When such a prediction needs
  another train's arrival (even spacing waits for the train behind), that train's own stop counts
  as its plain dwell rather than its hold, so on a circular line the question does not go round
  for ever.
- **One train per platform, in order.** All trains of a service are estimated together: a train
  cannot arrive before the one ahead has left, so the trains of a bunched line come out in the
  right order with a sensible gap between them. On a circular service, each train's next visit to
  each platform is considered once, in the order the trains approach that platform. A train's
  future lap never holds back another train that is already closer, and the last-to-first stretch
  uses the same queue rules as every other stretch.
- **Stretches no train has been timed on** get a guess from the length of the recorded track, or
  the straight-line distance, at the railway's usual speed (`timing.assumed-speed` until enough is
  learned). Such estimates are marked `~` on boards and shown grey on the website.
- **Late trains.** A train well past its usual time for the stretch, by more than the spread of
  the runs seen, is shown as *Delayed* rather than counting into negative numbers.

## Measuring it

ATCS scores itself. Before each arrival it keeps the estimate it showed 10, 5, 2 and 1 minutes
and 30 and 15 seconds earlier, and when the train arrives it records the error of each. The
figures are kept per service, stop and horizon in `data/eta-samples.yml` (bounded; the mean, the
bias and the worst case are kept, not every sample) and shown by:

```
/atcs timing report                 every service: mean error at 2 min, 1 min and 30 s
/atcs timing report <line/service>  the same per stop, with the five worst misses and when they were
/atcs timing reset                  start again, for instance after rebuilding a line
```

The mean is of the absolute error, in seconds; the bias in brackets says which way the estimate
leans when it leans at all (`+` means trains arrived later than said). `n` is how many arrivals
were scored. The worst case is one number because a single bad miss is what riders remember.

A report from a railway of four lines, ten services and 26 trains after a few days of running,
with recorded track and every stretch learned:

```
--- Arrival estimates ---
Mean error of the estimate shown 2 min, 1 min and 30 s before arrival (+ late, - early), over 107896 samples.
1/to-main: 2 min ±1 s, 1 min ±1 s, 30 s ±1 s; n=2379, worst 41 s
1/to-old-village: 2 min ±1 s, 1 min ±1 s, 30 s ±1 s; n=2384, worst 43 s
a/to-new-village: 2 min ±2 s, 1 min ±2 s, 30 s ±2 s; n=509, worst 45 s
nerdy/to-metal: 2 min ±1 s, 1 min ±1 s, 30 s ±1 s; n=3526, worst 59 s
s/to-1st-street: 2 min ±4 s, 1 min ±4 s, 30 s ±3 s; n=1179, worst 45 s
s/to-bay-area: 2 min ±2 s, 1 min ±2 s, 30 s ±2 s; n=1172, worst 37 s
```

A mean within a few seconds at two minutes out is what to expect once the track is recorded and
learned. The worst cases are single misses; the per-stop report says when each happened, and
`data/events.log` around that time usually shows what it was: a train held at a station sign for
a reason ATCS could not predict, a train unloaded mid-stretch and loaded again, a player's train
in the way.

## When a stop lies

Look at the per-stop report of the service first; the problem is usually one stop, not the
service.

- **A stop never scored** (`no samples`): its platform has no trigger, or the trigger is on the
  wrong platform. `/atcs lint` names platforms that never reported; `/atcs station here` shows
  what a platform's sign registers.
- **A large bias on one stretch** after the track changed: the learned time is from the old
  track. `/atcs timing reset` clears the scores only; `/atcs service resetpaths <line/service>`
  forgets the recorded path of one service so the next trains record it afresh, and
  `/atcs timing reset` after a few runs starts the scoring clean.
- **Wide errors on a shared stretch**: a slower service's trains are in the way and the hold
  behind them is not a station rule ATCS can predict. Give the station a `block` or `gap` rule
  so the wait becomes one ATCS controls, or turn on junction priority with recorded track.
- **Errors only at 30 s and 15 s**: the approach to the platform is odd (a long slow curve, a
  stop sign before the platform); the estimate counts down on position and cannot know. This is
  the one case `passenger.approach-distance` helps: *Now approaching* fires from the real
  distance, whatever the number says.
- **Still wrong**: turn on `logging.positions`, which logs every running train's place and
  speed twice a second to `data/events.log`, and compare with the estimate's story.
