Command: `docker stats`

Output:

```
CONTAINER ID   NAME                  CPU %     MEM USAGE / LIMIT    MEM %     NET I/O           BLOCK I/O         PIDS
23c6d8e960aa   bdi-vb-redis-1        1.15%     5.094MiB / 7.75GiB   0.06%     20.2MB / 7.52MB   1.39TB / 2.68MB   6
cb987b7f17d8   chat-app-grafana      1.54%     185.2MiB / 7.75GiB   2.33%     7.1MB / 1.48MB    2.82TB / 2MB      22
7970cde58fd1   chat-app-postgres     0.00%     89.87MiB / 7.75GiB   1.13%     15.4MB / 191MB    598GB / 49.1MB    22
5b7f32496f79   chat-app-redis        2.07%     8.883MiB / 7.75GiB   0.11%     73.2MB / 360MB    1.34TB / 1.76MB   6
466c793a7a4b   chat-app-prometheus   0.00%     75.27MiB / 7.75GiB   0.95%     1.07GB / 18.9MB   1.15TB / 1.36MB   15
3d18070d6c34   chat-app-rabbitmq     3.34%     5.281GiB / 7.75GiB   68.14%    3.47MB / 3.65MB   3.51TB / 1.02GB   5032
4aab32bf5a87   chat-app-minio        0.36%     138MiB / 7.75GiB     1.74%     10.9MB / 529MB    1.06TB / 4.01MB   16
```

RabbitMQ memory usage is 5.281GiB, why is it so high?

Investigation:

Generate the Internal Memory Breakdown (command is run inside the rabbitmq container):

```sh
rabbitmq-diagnostics memory_breakdown

Reporting memory breakdown on node rabbit@3d18070d6c34...
code: 0.0358 gb (34.18%)
other_system: 0.0265 gb (25.31%)
other_proc: 0.0163 gb (15.59%)
reserved_unallocated: 0.0146 gb (13.97%)
binary: 0.0028 gb (2.63%)
other_ets: 0.0027 gb (2.57%)
plugins: 0.002 gb (1.89%)
atom: 0.0019 gb (1.82%)
metrics: 0.0011 gb (1.03%)
mgmt_db: 0.0003 gb (0.29%)
msg_index: 0.0002 gb (0.22%)
metadata_store: 0.0001 gb (0.14%)
connection_other: 0.0001 gb (0.1%)
mnesia: 0.0001 gb (0.08%)
allocated_unused: 0.0 gb (0.05%)
connection_readers: 0.0 gb (0.03%)
metadata_store_ets: 0.0 gb (0.03%)
quorum_ets: 0.0 gb (0.02%)
queue_procs: 0.0 gb (0.02%)
connection_channels: 0.0 gb (0.01%)
connection_writers: 0.0 gb (0.0%)
quorum_queue_procs: 0.0 gb (0.0%)
quorum_queue_dlx_procs: 0.0 gb (0.0%)
stream_queue_procs: 0.0 gb (0.0%)
stream_queue_replica_reader_procs: 0.0 gb (0.0%)
queue_slave_procs: 0.0 gb (0.0%)
stream_queue_coordinator_procs: 0.0 gb (0.0%)
```

The reason docker stats reports 5 GB while RabbitMQ reports 104 MB is almost always due to the Linux Page Cache (OS File Cache), not an internal RabbitMQ memory leak.

Verify it is just Page Cache (command is run inside the rabbitmq container):

```sh
cat /sys/fs/cgroup/memory.stat

anon 4795621376
file 569593856
kernel 167936000
kernel_stack 81346560
pagetables 42930176
sec_pagetables 0
percpu 126200
sock 0
vmalloc 1236992
shmem 560095232
zswap 0
zswapped 0
file_mapped 491581440
file_dirty 0
file_writeback 0
swapcached 6160384
anon_thp 3059744768
file_thp 0
shmem_thp 0
inactive_anon 2696347648
active_anon 2665529344
inactive_file 1589248
active_file 7909376
unevictable 0
slab_reclaimable 4199912
slab_unreclaimable 32974328
slab 37174240
workingset_refault_anon 5330
workingset_refault_file 617749399
workingset_activate_anon 989
workingset_activate_file 61366432
workingset_restore_anon 980
workingset_restore_file 61009560
workingset_nodereclaim 0
pgdemote_kswapd 0
pgdemote_direct 0
pgdemote_khugepaged 0
pgscan 993187808
pgsteal 619776829
pgscan_kswapd 804415450
pgscan_direct 188763268
pgscan_khugepaged 9090
pgsteal_kswapd 503018093
pgsteal_direct 116754102
pgsteal_khugepaged 4634
pgfault 717480824
pgmajfault 22092074
pgrefill 593791715
pgactivate 189049445
pgdeactivate 0
pglazyfree 0
pglazyfreed 0
swpin_zero 36
swpout_zero 5323
zswpin 0
zswpout 0
zswpwb 0
thp_fault_alloc 52784
thp_collapse_alloc 1583
thp_swpout 0
thp_swpout_fallback 209
```

This output changes the diagnostic entirely: your 5GB footprint is not a passive file cache issue.

Your container is actively holding 4.47 GB of anonymous memory (anon) and 0.52 GB of shared memory (shmem). Because your internal rabbitmq-diagnostics tool only sees 104 MB, this means something else running inside your container (or an underlying memory allocator fragmentation bug) is hoarding your RAM.

Find the exact process consuming the 4.47 GB (command is run inside the rabbitmq container), this is the top result, we have about 150 lines like this:

```sh
ps aux --sort=-%mem

USER       PID %CPU %MEM    VSZ   RSS TTY      STAT START   TIME COMMAND
rabbitmq     1  3.1  1.2 1252056 101748 ?      Ssl  Aug02 150:42 /opt/erlang/lib/erlang/erts-14.2.5.12/bin/beam.smp -W w -MBas ageffcbf -MHas ageffcbf -MBlmbcs 5
root     22576  0.0  0.7 1185296 60316 ?       Sl   Aug03   2:06 /opt/rabbitmq/escript/rabbitmq-diagnostics -B -- -root /opt/erlang/lib/erlang -bindir /opt/erlan
root     19481  0.0  0.7 1184744 57768 ?       Sl   Aug03   0:21 /opt/rabbitmq/escript/rabbitmq-diagnostics -B -- -root /opt/erlang/lib/erlang -bindir /opt/erlan
root     47562  0.0  0.7 1190916 57588 ?       Sl   Aug03   0:19 /opt/rabbitmq/escript/rabbitmq-diagnostics -B -- -root /opt/erlang/lib/erlang -bindir /opt/erlan
root     62354  0.0  0.6 1188172 56400 ?       Sl   Aug04   0:20 /opt/rabbitmq/escript/rabbitmq-diagnostics -B -- -root /opt/erlang/lib/erlang -bindir /opt/erlan
root     11196  0.0  0.6 1183560 56232 ?       Sl   Aug05   0:11 /opt/rabbitmq/escript/rabbitmq-diagnostics -B -- -root /opt/erlang/lib/erlang -bindir /opt/erlan
root     91769  0.0  0.6 1185048 56140 ?       Sl   Aug03   0:22 /opt/rabbitmq/escript/rabbitmq-diagnostics -B -- -root /opt/erlang/lib/erlang -bindir /opt/erlan
root     14349  0.0  0.6 1184760 55984 ?       Sl   Aug03   0:21 /opt/rabbitmq/escript/rabbitmq-diagnostics -B -- -root /opt/erlang/lib/erlang -bindir /opt/erlan
root     70246  0.0  0.6 1184712 55788 ?       Sl   Aug04   0:22 /opt/rabbitmq/escript/rabbitmq-diagnostics -B -- -root /opt/erlang/lib/erlang -bindir /opt/erlan
...
```

This output completely exposes the root cause, and it is a fascinating, known issue with how health checks are interacting with your Docker setup.

Look closely at your process list: you have a massive pile of zombie rabbitmq-diagnostics processes running as root (PIDs 22576, 19481, 47562, etc.). Each one is eating ~56MB of RAM.

Your main RabbitMQ process (PID 1) is actually completely healthy and only using a tiny 101 MB (RSS).
The other 4.4+ GB of anon memory is being leaked by your Docker Healthcheck.

1.  Docker is configured to regularly run rabbitmq-diagnostics inside the container to see if the node is alive.
2.  For some reason (usually a timeout, network hang, or standard Erlang CLI tool behavior), these health-check commands are not exiting completely.
3.  They get stuck in the background, pile up over days (notice the start dates spanning from Aug 03 to Aug 05), and continuously hoard your RAM.
