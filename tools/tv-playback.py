#!/usr/bin/env python3
"""Playback measurements for Telly over adb (ADR-028).

  TV_HOST=192.168.1.50 tools/tv-playback.py <command>

Commands
  freezes       past viewing on the TV: how long each channel played and every freeze, read from
                Android's own audio log (`dumpsys media.metrics`, a few hours deep). Nothing has to
                be running and the TV can be in standby.
                  --min-seconds 20  stays shorter than this (zapping) are left out of the list and
                                    of the totals alike; compare builds with the same value
  channels      what kind of streams the lineup holds (HLS, MPEG-TS, YouTube...), as shapes
  probe         per live HLS channel: redirects with timings, playlist shape, one segment's speed
                  --channels 5,9   lineup numbers (default: the first three)
                  --segments       download the oldest, middle and newest segments and compare
                  --watch 45       poll the playlist for 45 s and show when it advances
                  --save DIR       keep the newest segment in DIR, e.g. to look at it with ffprobe
  guide-call    does the guide call made on a channel change delay the stream's first hop?
                  --count 6
  model         a real-time model of ExoPlayer's live HLS rules against the real provider, one run
                per channel and setting: first picture, freezes, buffer
                  --channels 3,24  --seconds 60  --settings before,now
                settings: before (ADR-028's starting point: 6 s live offset, buffer 3 to 30 s),
                now (three segments behind, buffer 30 s), start2.5 (now, picture after 2.5 s)
                It counts video as playable in proportion to the bytes downloaded and decodes
                nothing, so its first-picture times are an estimate; freezes and buffer are what
                it is for.

The TV's channel list holds stream addresses with the subscription's username and password in
them. These commands read it over adb (the installed build must be debuggable, as CI builds are)
and print timings and shapes only: never an address, a host name or a credential. Keep it that way.

probe, guide-call and model open streams from this computer with the TV's subscription. Most
accounts allow one stream at a time, so run them while the TV is in standby or on a YouTube channel.

Environment: TV_HOST (required), TV_PORT (5555), ADB (adb).
"""
import argparse
import collections
import datetime
import http.client
import json
import os
import re
import socket
import statistics
import subprocess
import sys
import threading
import time
from urllib.parse import quote, urljoin, urlparse

APP = "com.ielali.simpletv"
USER_AGENT = "SimpleTV/0.1 (Android TV)"
REDIRECTS = (301, 302, 303, 307, 308)


def quiet_failure(exc_type, exc, tb):
    """Errors from HTTP code can carry an address; say what failed and where, nothing more."""
    while tb is not None and tb.tb_next is not None:
        tb = tb.tb_next
    line = tb.tb_lineno if tb is not None else 0
    print("failed: %s at line %d (details withheld: they could contain a stream address)" % (exc_type.__name__, line), file=sys.stderr)


sys.excepthook = quiet_failure
threading.excepthook = lambda a: quiet_failure(a.exc_type, a.exc_value, a.exc_traceback)


# ---------------------------------------------------------------- adb

def adb(*args):
    host = os.environ.get("TV_HOST")
    if not host:
        sys.exit("set TV_HOST to the TV's IP address, e.g. TV_HOST=192.168.1.50 tools/tv-playback.py freezes")
    serial = "%s:%s" % (host, os.environ.get("TV_PORT", "5555"))
    exe = os.environ.get("ADB", "adb")
    subprocess.run([exe, "connect", serial], capture_output=True)
    done = subprocess.run([exe, "-s", serial] + list(args), capture_output=True)
    if done.returncode != 0:
        sys.exit("adb could not reach the TV at %s" % serial)
    return done.stdout.decode("utf-8", "replace")


def lineup():
    try:
        return json.loads(adb("exec-out", "run-as", APP, "cat", "files/channels.json"))["channels"]
    except (ValueError, KeyError):
        sys.exit("could not read the channel list (is Telly installed, and a debuggable build?)")


def live_hls(channels):
    found = [c for c in channels if c.get("type", "STREAM") == "STREAM" and not c.get("playlist") and ".m3u8" in c.get("url", "")]
    return sorted(found, key=lambda c: c["number"])


def pick(channels, wanted, default_count):
    if not wanted:
        return channels[:default_count]
    numbers = [int(n) for n in wanted.split(",")]
    by_number = {c["number"]: c for c in channels}
    missing = [n for n in numbers if n not in by_number]
    if missing:
        sys.exit("not live HLS channels in the lineup: %s" % missing)
    return [by_number[n] for n in numbers]


# ---------------------------------------------------------------- output without addresses

_hosts = {}


def host_label(netloc):
    return _hosts.setdefault(netloc, "host%d" % (len(_hosts) + 1))


def ms(seconds):
    return "%dms" % round(seconds * 1000)


def address_shape(url):
    p = urlparse(url)
    parts = [s for s in p.path.split("/") if s]
    ext = "." + parts[-1].rsplit(".", 1)[1] if parts and "." in parts[-1] else ""
    kept = ("live", "movie", "series", "hls")
    middle = "/".join(s if s in kept else "*" for s in parts[:-1])
    return "%s://%s/%s/<id>%s%s" % (p.scheme, host_label(p.netloc), middle, ext, "?…" if p.query else "")


# ---------------------------------------------------------------- HTTP

def connection(p, timeout=20):
    cls = http.client.HTTPSConnection if p.scheme == "https" else http.client.HTTPConnection
    return cls(p.hostname, p.port, timeout=timeout)


def target(p):
    return p.path + ("?" + p.query if p.query else "")


def headers_for(channel):
    return dict({"User-Agent": USER_AGENT}, **channel.get("headers", {}))


def timed_get(url, headers):
    """One GET on a new connection, redirects not followed. Returns status, headers, body, timings, progress."""
    p = urlparse(url)
    t0 = time.monotonic()
    socket.getaddrinfo(p.hostname, p.port or (443 if p.scheme == "https" else 80))
    t_dns = time.monotonic()
    c = connection(p)
    c.connect()
    t_connect = time.monotonic()
    c.request("GET", target(p), headers=headers)
    r = c.getresponse()
    t_first = time.monotonic()
    chunks, got, progress = [], 0, []
    while True:
        b = r.read(65536)
        if not b:
            break
        chunks.append(b)
        got += len(b)
        progress.append((time.monotonic() - t_dns, got))
    t_end = time.monotonic()
    response_headers = {k.lower(): v for k, v in r.getheaders()}
    c.close()
    timings = dict(dns=t_dns - t0, connect=t_connect - t_dns, first_byte=t_first - t_connect, body=t_end - t_first, total=t_end - t0)
    return r.status, response_headers, b"".join(chunks), timings, progress


def follow(url, headers, hops=5):
    """GET following redirects, each hop on a new connection. Returns final url, status, headers, body, hops, progress."""
    chain = []
    for _ in range(hops):
        status, response_headers, body, timings, progress = timed_get(url, headers)
        chain.append((status, urlparse(url), timings))
        if status in REDIRECTS and "location" in response_headers:
            url = urljoin(url, response_headers["location"])
            continue
        break
    return url, status, response_headers, body, chain, progress


_local = threading.local()


def pooled_get(url, headers, on_progress=None, hops=5):
    """GET following redirects on kept-alive connections (one pool per thread), as a player would."""
    pool = getattr(_local, "pool", None)
    if pool is None:
        pool = _local.pool = {}
    for _ in range(hops):
        p = urlparse(url)
        for attempt in (0, 1):
            c = pool.get(p.netloc)
            if c is None:
                c = pool[p.netloc] = connection(p)
            try:
                c.request("GET", target(p), headers=headers)
                r = c.getresponse()
                break
            except Exception:
                c.close()
                pool.pop(p.netloc, None)
                if attempt:
                    raise
        location = r.getheader("Location")
        if r.status in REDIRECTS and location:
            r.read()
            url = urljoin(url, location)
            continue
        total = int(r.getheader("Content-Length") or 0)
        chunks, got = [], 0
        while True:
            b = r.read(32768)
            if not b:
                break
            got += len(b)
            if on_progress:
                on_progress(got, total)
            else:
                chunks.append(b)
        return url, r.status, b"".join(chunks)
    raise IOError("too many redirects")


# ---------------------------------------------------------------- HLS playlists

def parse_playlist(text):
    lines = [l.strip() for l in text.splitlines() if l.strip()]
    info = dict(master=False, target=10.0, sequence=0, segments=[], dated=False, ended=False, variants=0, tags=set())
    duration = None
    for l in lines:
        if l.startswith("#EXT"):
            info["tags"].add(l.split(":")[0])
        if l.startswith("#EXT-X-STREAM-INF"):
            info["master"] = True
            info["variants"] += 1
        elif l.startswith("#EXT-X-TARGETDURATION:"):
            info["target"] = float(l.split(":")[1])
        elif l.startswith("#EXT-X-MEDIA-SEQUENCE:"):
            info["sequence"] = int(l.split(":")[1])
        elif l.startswith("#EXTINF:"):
            duration = float(l.split(":")[1].split(",")[0])
        elif l.startswith("#EXT-X-PROGRAM-DATE-TIME"):
            info["dated"] = True
        elif l.startswith("#EXT-X-ENDLIST"):
            info["ended"] = True
        elif not l.startswith("#") and duration is not None:
            info["segments"].append((info["sequence"] + len(info["segments"]), duration, l))
            duration = None
    return info


# ---------------------------------------------------------------- channels

def cmd_channels(args):
    channels = lineup()
    counts = collections.Counter()
    for c in channels:
        kind = c.get("type", "STREAM")
        if kind != "STREAM":
            counts[(kind, "playlist" if "list=" in c.get("url", "") else "video or channel")] += 1
        elif c.get("playlist"):
            counts[("STREAM", "film or series channel")] += 1
        else:
            agent = c.get("headers", {}).get("User-Agent")
            counts[("STREAM", address_shape(c.get("url", "")) + (" with its own User-Agent" if agent else ""))] += 1
    print("channels: %d" % len(channels))
    for (kind, shape), n in counts.most_common():
        print("%4d  %-8s %s" % (n, kind, shape))
    print("numbers: %s" % sorted(c.get("number") for c in channels))


# ---------------------------------------------------------------- freezes

def cmd_freezes(args):
    listed = adb("shell", "cmd", "package", "list", "packages", "-U", APP)
    found = re.search(r"package:%s uid:(\d+)" % re.escape(APP), listed)
    if not found:
        sys.exit("Telly is not installed on the TV")
    uid = found.group(1)
    record = re.compile(r"\{(audio\.track\.\d+), \((\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\), \(\S+, \d+, (\d+)\), \((.*)\)\}\s*$")
    tracks, seen = collections.OrderedDict(), set()
    for line in adb("shell", "dumpsys", "media.metrics").splitlines():
        m = record.search(line)
        if not m or m.group(3) != uid:
            continue
        key, stamp, _, body = m.groups()
        if (key, stamp, body) in seen:     # the dump lists every record twice
            continue
        seen.add((key, stamp, body))
        fields = dict(part.split("=", 1) for part in body.split(", ") if "=" in part)
        if "event#" in fields:
            # No year in the log; a leap year keeps 29 February readable. Only differences matter.
            at = datetime.datetime.strptime("2000-" + stamp, "%Y-%m-%d %H:%M:%S.%f")
            tracks.setdefault(key, []).append((at, fields["event#"]))
    stays = []
    for events in tracks.values():
        created = next((at for at, e in events if e == "create"), None)
        if created is None:
            continue
        freezes, played, started, paused = [], 0.0, None, None
        for at, event in events:
            if event == "start":
                if paused is not None and (at - paused).total_seconds() >= 0.25:
                    freezes.append(((paused - created).total_seconds(), (at - paused).total_seconds()))
                started, paused = at, None
            elif event == "pause":
                if started is not None:
                    played += (at - started).total_seconds()
                paused, started = at, None
            elif event in ("flush", "stop", "dtor", "server.dtor"):
                if started is not None:
                    played += (at - started).total_seconds()
                    started = None
                if event != "flush":
                    paused = None
        stays.append(dict(at=created, on=(events[-1][0] - created).total_seconds(), played=played, freezes=freezes))
    if not stays:
        print("no playback by Telly in the TV's audio log (it keeps a few hours)")
        return
    shown = [s for s in stays if s["on"] >= args.min_seconds]
    print("One line per time the sound started on a channel (a tune, or a retry). A freeze is the sound")
    print("pausing and starting again; a film paused with OK looks the same.")
    print()
    for s in shown:
        frozen_at = " ".join("after %.0fs for %.1fs" % f for f in s["freezes"])
        print("%s  on %5.0fs  played %5.0fs  freezes %d  %s" % (s["at"].strftime("%m-%d %H:%M:%S"), s["on"], s["played"], len(s["freezes"]), frozen_at))
    # The totals cover the stays listed above and no others, so two builds compare like with like.
    freezes = [f for s in shown for f in s["freezes"]]
    frozen = sum(f[1] for f in freezes)
    played = sum(s["played"] for s in shown)
    print()
    print("stays of %ds or more: %d, of which froze: %d (%d shorter stays left out; --min-seconds 0 counts them)" % (
        args.min_seconds, len(shown), sum(1 for s in shown if s["freezes"]), len(stays) - len(shown)))
    print("freezes: %d, %.1fs frozen against %.0fs played (%.1f%% of the time)" % (len(freezes), frozen, played, 100 * frozen / max(1.0, frozen + played)))
    if freezes:
        print("freeze length: median %.1fs, longest %.1fs" % (statistics.median(f[1] for f in freezes), max(f[1] for f in freezes)))


# ---------------------------------------------------------------- probe

def cmd_probe(args):
    for channel in pick(live_hls(lineup()), args.channels, 3):
        headers = headers_for(channel)
        print("=== channel %d" % channel["number"])
        final, status, response_headers, body, chain, _ = follow(channel["url"], headers)
        for hop_status, p, t in chain:
            print("  hop %s %s://%s  dns %s  connect %s  first byte %s  body %s" % (
                hop_status, p.scheme, host_label(p.netloc), ms(t["dns"]), ms(t["connect"]), ms(t["first_byte"]), ms(t["body"])))
        print("  playlist in %s: status %s, %s, server %s, %d bytes" % (
            ms(sum(t["total"] for _, _, t in chain)), status, response_headers.get("content-type"), response_headers.get("server"), len(body)))
        if status != 200:
            continue
        info = parse_playlist(body.decode("utf-8", "replace"))
        if info["master"]:
            print("  a list of %d qualities, not a media playlist; not followed" % info["variants"])
            continue
        segments = info["segments"]
        if not segments:
            print("  no segments in the playlist")
            continue
        lengths = [d for _, d, _ in segments]
        print("  target %.0fs, %d segments (%.0fs), segment length min/median/max %.1f/%.1f/%.1fs, dated %s, ended %s" % (
            info["target"], len(segments), sum(lengths), min(lengths), statistics.median(lengths), max(lengths), info["dated"], info["ended"]))
        print("  tags: %s" % " ".join(sorted(info["tags"])))
        print("  segments at: %s" % address_shape(urljoin(final, segments[-1][2])))
        if args.segments:
            chosen = [("oldest", 0), ("middle", len(segments) // 2), ("second newest", len(segments) - 2), ("newest", len(segments) - 1)]
        else:
            chosen = [("newest", len(segments) - 1)]
        for label, index in chosen:
            _, length, uri = segments[max(index, 0)]
            _, seg_status, seg_headers, seg_body, seg_chain, progress = follow(urljoin(final, uri), headers)
            t = seg_chain[-1][2]
            took = t["connect"] + t["first_byte"] + t["body"]
            size = len(seg_body)

            def reached(fraction):
                return next((at for at, got in progress if got >= size * fraction), took)

            print("  %-13s status %s  %7d bytes  first byte %s  10%% %.2fs  50%% %.2fs  all %.2fs  = %.1f Mbit/s for a %.2f Mbit/s stream (%.1fs)%s" % (
                label, seg_status, size, ms(t["connect"] + t["first_byte"]), reached(0.1), reached(0.5), took,
                size * 8 / took / 1e6 if took else 0, size * 8 / length / 1e6 if length else 0, length,
                "" if "content-length" in seg_headers else "  (no length: still being written?)"))
            if args.save and label == "newest" and seg_status == 200:
                os.makedirs(args.save, exist_ok=True)
                with open(os.path.join(args.save, "channel-%d.ts" % channel["number"]), "wb") as f:
                    f.write(seg_body)
        if args.watch:
            last, began, moves = segments[-1][0], time.monotonic(), []
            while time.monotonic() - began < args.watch:
                time.sleep(1)
                try:
                    _, st, text = pooled_get(channel["url"], headers)
                    now = parse_playlist(text.decode("utf-8", "replace"))["segments"]
                    if st == 200 and now and now[-1][0] != last:
                        moves.append("%.0fs +%d" % (time.monotonic() - began, now[-1][0] - last))
                        last = now[-1][0]
                except Exception as e:
                    moves.append("%.0fs %s" % (time.monotonic() - began, type(e).__name__))
            print("  playlist advanced at: %s" % ", ".join(moves))


# ---------------------------------------------------------------- guide call

def cmd_guide_call(args):
    channels = live_hls(lineup())
    if len(channels) < 2:
        sys.exit("needs two live HLS channels in the lineup")

    def first_hop(channel):
        status, _, _, t, _ = timed_get(channel["url"], headers_for(channel))
        return t["connect"] + t["first_byte"] + t["body"], status

    def guide(channel):
        p = urlparse(channel["url"])
        parts = p.path.strip("/").split("/")
        if len(parts) < 4:
            return None
        url = "%s://%s/player_api.php?username=%s&password=%s&action=get_short_epg&stream_id=%s&limit=4" % (
            p.scheme, p.netloc, quote(parts[1]), quote(parts[2]), parts[3].split(".")[0])
        status, _, _, t, _ = timed_get(url, headers_for(channel))
        return t["connect"] + t["first_byte"] + t["body"], status

    alone, together, calls = [], [], []
    for i in range(args.count):
        channel, other = channels[i % len(channels)], channels[(i + 1) % len(channels)]
        a, a_status = first_hop(channel)
        time.sleep(0.5)
        result = {}
        thread = threading.Thread(target=lambda: result.update(guide=guide(channel)))
        thread.start()
        time.sleep(0.02)
        b, b_status = first_hop(other)
        thread.join()
        if result.get("guide") is None:
            sys.exit("not an Xtream stream address; nothing to compare")
        alone.append(a)
        together.append(b)
        calls.append(result["guide"][0])
        print("stream's first hop alone %.2fs (status %s) | with a guide call under way %.2fs (status %s) | the guide call %.2fs (status %s)" % (
            a, a_status, b, b_status, result["guide"][0], result["guide"][1]))
        time.sleep(0.5)
    print("median: alone %.2fs, with a guide call %.2fs, the guide call %.2fs" % (
        statistics.median(alone), statistics.median(together), statistics.median(calls)))


# ---------------------------------------------------------------- model

# hold_back: seconds behind the playlist's end the player asks for; None is HLS's three target durations.
# low/high: the load control stops loading at `high` seconds buffered and starts again below `low`.
SETTINGS = {
    "before": dict(hold_back=6.0, low=3.0, high=30.0, start=1.0, resume=2.0),
    "now": dict(hold_back=None, low=30.0, high=30.0, start=1.0, resume=2.0),
    "start2.5": dict(hold_back=None, low=30.0, high=30.0, start=2.5, resume=2.0),
}


class ModelPlayer:
    """ExoPlayer's rules for a live HLS media playlist, in real time, without decoding anything.

    Start position: the segment holding (playlist end - hold back), as HlsMediaSource picks it.
    Playlist: reread one target duration after a load that changed it, half that after one that did
    not (DefaultHlsPlaylistTracker). Segments: one at a time, while the load control allows.
    Picture: starts with `start` seconds buffered, resumes after a freeze with `resume`.
    """

    def __init__(self, channel, name, seconds):
        self.channel, self.name, self.seconds = channel, name, seconds
        self.rules = SETTINGS[name]
        self.headers = headers_for(channel)
        self.lock = threading.Lock()
        self.segments = {}
        self.base = None
        self.target = 10.0
        self.newest = None
        self.oldest = None
        self.loaded = 0.0       # seconds of video downloaded, from the start position
        self.played = 0.0
        self.stopping = False
        self.errors = 0
        self.behind = None
        self.fell_out = 0

    def playlist_loop(self):
        while not self.stopping:
            began, changed = time.monotonic(), False
            try:
                final, status, body = pooled_get(self.channel["url"], self.headers)
                info = parse_playlist(body.decode("utf-8", "replace")) if status == 200 else None
                if info and info["segments"]:
                    with self.lock:
                        self.base, self.target = final, info["target"]
                        changed = info["segments"][-1][0] != self.newest
                        self.newest = info["segments"][-1][0]
                        self.oldest = info["segments"][0][0]
                        for sequence, length, uri in info["segments"]:
                            self.segments[sequence] = (length, uri)
                else:
                    self.errors += 1
            except Exception:
                self.errors += 1
            until = began + (self.target if changed else self.target / 2)
            while not self.stopping and time.monotonic() < until:
                time.sleep(0.05)

    def loader_loop(self):
        while self.newest is None and not self.stopping:
            time.sleep(0.01)
        if self.stopping:
            return
        with self.lock:
            order = sorted(self.segments)
            total = sum(self.segments[s][0] for s in order)
            hold_back = self.rules["hold_back"] if self.rules["hold_back"] is not None else 3 * self.target
            wanted, at, first = total - hold_back, 0.0, order[0]
            for s in order:
                if at <= wanted:
                    first = s
                at += self.segments[s][0]
            self.behind = sum(self.segments[s][0] for s in order if s >= first)
        following, loading = first, True
        while not self.stopping:
            buffered = self.loaded - self.played
            if buffered < self.rules["low"]:
                loading = True
            elif buffered >= self.rules["high"]:
                loading = False
            with self.lock:
                segment, base = self.segments.get(following), self.base
            if not loading or segment is None:
                time.sleep(0.05)
                continue
            length, uri = segment
            before = self.loaded

            def on_progress(got, total, length=length, before=before):
                if total:
                    self.loaded = before + length * got / total

            try:
                _, status, _ = pooled_get(urljoin(base, uri), self.headers, on_progress=on_progress)
            except Exception:
                status = 0
            if status == 200:
                self.loaded = before + length
                following += 1
                continue
            self.errors += 1
            self.loaded = before
            with self.lock:
                if self.oldest is not None and following < self.oldest:
                    # Fell out of the playlist: the engine jumps back to live.
                    self.fell_out += 1
                    following = self.newest
            time.sleep(1)

    def run(self):
        began = time.monotonic()
        threads = [threading.Thread(target=self.playlist_loop, daemon=True), threading.Thread(target=self.loader_loop, daemon=True)]
        for t in threads:
            t.start()
        playing, first_picture, froze_at, freezes, samples = False, None, None, [], []
        last = time.monotonic()
        while time.monotonic() - began < self.seconds:
            time.sleep(0.02)
            now = time.monotonic()
            step, last = now - last, now
            buffered = self.loaded - self.played
            if playing:
                self.played += min(step, max(buffered, 0.0))
                if buffered <= 0.02:
                    playing, froze_at = False, now
                elif int(now * 2) != int((now - step) * 2):
                    samples.append(buffered)
            elif buffered >= (self.rules["start"] if first_picture is None else self.rules["resume"]):
                playing = True
                if first_picture is None:
                    first_picture = now - began
                else:
                    freezes.append((froze_at - began, now - froze_at))
        if not playing and first_picture is not None and froze_at is not None:
            freezes.append((froze_at - began, time.monotonic() - froze_at))
        self.stopping = True
        for t in threads:
            t.join(timeout=25)
        return dict(name=self.name, number=self.channel["number"], first=first_picture, freezes=freezes,
                    frozen=sum(f[1] for f in freezes), played=self.played, behind=self.behind,
                    low=min(samples) if samples else 0.0, average=statistics.mean(samples) if samples else 0.0,
                    errors=self.errors, fell_out=self.fell_out)


def cmd_model(args):
    names = args.settings.split(",")
    unknown = [n for n in names if n not in SETTINGS]
    if unknown:
        sys.exit("unknown settings %s; there are: %s" % (unknown, ", ".join(SETTINGS)))
    results = []
    for i, channel in enumerate(pick(live_hls(lineup()), args.channels, 3)):
        for name in (names if i % 2 == 0 else names[::-1]):     # alternate the order: the line drifts
            r = ModelPlayer(channel, name, args.seconds).run()
            results.append(r)
            print("%-8s ch %3d: first picture %s, %s behind the playlist's end, played %.0fs, freezes %d %s, buffer lowest/average %.1f/%.1fs, errors %d%s" % (
                r["name"], r["number"], "%.1fs" % r["first"] if r["first"] is not None else "never",
                "%.0fs" % r["behind"] if r["behind"] is not None else "?", r["played"], len(r["freezes"]),
                " ".join("(after %.0fs for %.1fs)" % f for f in r["freezes"]), r["low"], r["average"], r["errors"],
                ", fell out of the playlist %d times" % r["fell_out"] if r["fell_out"] else ""), flush=True)
            time.sleep(2)
    print()
    for name in names:
        runs = [r for r in results if r["name"] == name]
        firsts = [r["first"] for r in runs if r["first"] is not None]
        frozen, played = sum(r["frozen"] for r in runs), sum(r["played"] for r in runs)
        print("%-8s %d runs of %.0fs | first picture median %s | runs that froze %d | freezes %d | frozen %.1fs of %.0fs (%.1f%%) | average buffer %.1fs" % (
            name, len(runs), args.seconds, "%.1fs" % statistics.median(firsts) if firsts else "never",
            sum(1 for r in runs if r["freezes"]), sum(len(r["freezes"]) for r in runs), frozen, frozen + played,
            100 * frozen / max(1e-9, frozen + played), statistics.mean(r["average"] for r in runs)))


# ---------------------------------------------------------------- main

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    commands.add_parser("channels").set_defaults(run=cmd_channels)

    freezes = commands.add_parser("freezes")
    freezes.add_argument("--min-seconds", type=int, default=20, help="leave shorter stays out of the list and the totals (default 20)")
    freezes.set_defaults(run=cmd_freezes)

    probe = commands.add_parser("probe")
    probe.add_argument("--channels")
    probe.add_argument("--segments", action="store_true")
    probe.add_argument("--watch", type=int, default=0)
    probe.add_argument("--save")
    probe.set_defaults(run=cmd_probe)

    guide_call = commands.add_parser("guide-call")
    guide_call.add_argument("--count", type=int, default=6)
    guide_call.set_defaults(run=cmd_guide_call)

    model = commands.add_parser("model")
    model.add_argument("--channels")
    model.add_argument("--seconds", type=float, default=60)
    model.add_argument("--settings", default="before,now")
    model.set_defaults(run=cmd_model)

    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
