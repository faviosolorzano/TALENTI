"""Supervisor sin red ni socket Docker. Ningún contenido se imprime en registros."""
import concurrent.futures
import ctypes
import errno
import json
import math
import multiprocessing
import os
from pathlib import Path
import re
import resource
import selectors
import signal
import subprocess
import sys
import tempfile
import time
import uuid
import shutil
import stat

JOBS = Path("/jobs")
OWNER = 10001
LIBC = ctypes.CDLL(None, use_errno=True)


class CapHeader(ctypes.Structure):
    _fields_ = [("version", ctypes.c_uint32), ("pid", ctypes.c_int)]


class CapData(ctypes.Structure):
    _fields_ = [("effective", ctypes.c_uint32), ("permitted", ctypes.c_uint32), ("inheritable", ctypes.c_uint32)]


def capabilities(mask, inheritable=0):
    header = CapHeader(0x20080522, 0)
    data = (CapData * 2)()
    data[0] = CapData(mask, mask, inheritable)
    if LIBC.capset(ctypes.byref(header), ctypes.byref(data)):
        raise RuntimeError("capabilities-unavailable")


def boot():
    if os.getuid() == 0:
        # Docker no entrega capacidades efectivas a --user no-root. Se conservan solo
        # las tres aprobadas y se abandona root antes de tini, la cola o cualquier dato.
        if LIBC.prctl(8, 1, 0, 0, 0):  # PR_SET_KEEPCAPS
            raise RuntimeError("capabilities-unavailable")
        os.setgroups([])
        os.setresgid(OWNER, OWNER, OWNER)
        os.setresuid(OWNER, OWNER, OWNER)
        mask = (1 << 5) | (1 << 6) | (1 << 7)
        capabilities(mask, mask)
        for cap in (5, 6, 7):
            if LIBC.prctl(47, 2, cap, 0, 0):  # PR_CAP_AMBIENT, RAISE
                raise RuntimeError("capabilities-unavailable")
        os.execv("/sbin/tini", ["tini", "--", sys.executable, "-u", __file__, "--supervisor"])
    if os.getuid() != OWNER:
        raise RuntimeError("unexpected-supervisor-user")


SECCOMP = ctypes.CDLL("libseccomp.so.2")
SECCOMP.seccomp_init.argtypes = [ctypes.c_uint32]
SECCOMP.seccomp_init.restype = ctypes.c_void_p
SECCOMP.seccomp_rule_add.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_int, ctypes.c_uint]
SECCOMP.seccomp_load.argtypes = [ctypes.c_void_p]
SECCOMP.seccomp_release.argtypes = [ctypes.c_void_p]
SECCOMP.seccomp_syscall_resolve_name.argtypes = [ctypes.c_char_p]


def no_network():
    ctx = SECCOMP.seccomp_init(0x7FFF0000)  # ALLOW, excepto sockets del programa
    if not ctx:
        raise RuntimeError("seccomp-unavailable")
    try:
        for name in ("socket", "socketpair", "connect", "bind", "listen", "accept", "accept4"):
            number = SECCOMP.seccomp_syscall_resolve_name(name.encode())
            if number >= 0 and SECCOMP.seccomp_rule_add(ctx, 0x00050000 | errno.EPERM, number, 0):
                raise RuntimeError("seccomp-unavailable")
        if SECCOMP.seccomp_load(ctx):
            raise RuntimeError("seccomp-unavailable")
    finally:
        SECCOMP.seccomp_release(ctx)


def user(uid):
    os.setegid(uid)
    os.seteuid(uid)


def owner():
    os.seteuid(OWNER)
    os.setegid(OWNER)


def processes(uid):
    found = []
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit():
            continue
        try:
            fields = dict(line.split(":", 1) for line in (entry / "status").read_text().splitlines() if ":" in line)
            if int(fields["Uid"].split()[0]) == uid and not fields["State"].strip().startswith("Z"):
                found.append((int(entry.name), int(fields.get("VmRSS", "0 kB").split()[0]) * 1024))
        except (OSError, KeyError, ValueError):
            pass
    return found


def kill_user(uid):
    # También corta descendientes que hicieron setsid y escaparon del grupo original.
    for _ in range(50):
        children = processes(uid)
        if not children:
            return
        for pid, _ in children:
            try:
                os.kill(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        time.sleep(0.005)
    raise RuntimeError("process-cleanup-failed")


def clean_files(uid):
    # /tmp es un tmpfs exclusivo del ejecutor. Un programa puede renombrar su
    # carpeta o quitar sus permisos: se limpian todos sus objetos, sin seguir enlaces.
    try:
        user(uid)
        for path in Path("/tmp").iterdir():
            info = path.lstat()
            if info.st_uid != uid:
                continue
            if not stat.S_ISDIR(info.st_mode):
                path.unlink(missing_ok=True)
                continue
            path.chmod(0o700)
            for directory, dirs, _ in os.walk(path, topdown=True, followlinks=False):
                for name in dirs:
                    child = Path(directory) / name
                    if not child.is_symlink():
                        child.chmod(0o700)
            shutil.rmtree(path)
    finally:
        owner()


def seconds(value, default, maximum):
    if isinstance(value, (int, float)):
        result = float(value)
    else:
        match = re.fullmatch(r"PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?", str(value))
        result = sum(float(x or 0) * factor for x, factor in zip(match.groups(), (3600, 60, 1))) if match else default
    return max(0.05, min(result, maximum))


def execute(args, workspace, uid, input_text, timeout, limits, job):
    started = time.monotonic()
    maximum = min(int(limits.get("outputBytes", 65536)), 65536)
    memory = min(int(limits.get("memoryMb", 256)), 256) * 1024 * 1024

    def sandbox():
        os.setsid()
        os.setgroups([])
        os.setresgid(uid, uid, uid)
        os.setresuid(uid, uid, uid)
        capabilities(0)
        resource.setrlimit(resource.RLIMIT_CPU, (math.ceil(timeout), math.ceil(timeout) + 1))
        resource.setrlimit(resource.RLIMIT_NPROC, (64, 64))
        resource.setrlimit(resource.RLIMIT_NOFILE, (64, 64))
        resource.setrlimit(resource.RLIMIT_FSIZE, (1024 * 1024, 1024 * 1024))
        resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
        os.chdir(workspace)
        no_network()

    env = {"PATH": "/opt/java/openjdk/bin:/usr/local/bin:/usr/bin:/bin", "JAVA_HOME": "/opt/java/openjdk",
           "HOME": str(workspace), "TMPDIR": str(workspace), "LANG": "C.UTF-8", "PYTHONHASHSEED": "0"}
    output = bytearray()
    error = "NONE"
    with tempfile.TemporaryFile() as stdin:
        stdin.write(input_text.encode())
        stdin.seek(0)
        process = subprocess.Popen(args, stdin=stdin, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   preexec_fn=sandbox, env=env, close_fds=True)
        selector = selectors.DefaultSelector()
        for stream in (process.stdout, process.stderr):
            os.set_blocking(stream.fileno(), False)
            selector.register(stream, selectors.EVENT_READ)
        size = 0
        try:
            while selector.get_map() or process.poll() is None:
                if time.monotonic() - started >= timeout or not job.is_dir() or (job / "cancel").exists():
                    error = "TIMEOUT"
                    break
                if sum(rss for _, rss in processes(uid)) > memory:
                    error = "MEMORY_LIMIT"
                    break
                for key, _ in selector.select(0.01):
                    data = os.read(key.fileobj.fileno(), 8192)
                    if not data:
                        selector.unregister(key.fileobj)
                        continue
                    size += len(data)
                    if key.fileobj is process.stdout:
                        output.extend(data[:max(0, maximum - len(output))])
                    if size > maximum:
                        error = "OUTPUT_LIMIT"
                        break
                if error != "NONE":
                    break
        finally:
            kill_user(uid)
            process.wait(timeout=2)
            selector.close()
            process.stdout.close()
            process.stderr.close()
    if error == "NONE" and process.returncode != 0:
        error = "RUNTIME"
    text = output.decode("utf-8", errors="replace").encode("utf-8")[:maximum].decode("utf-8", errors="ignore")
    return {"completed": error == "NONE", "output": text,
            "error": error, "ms": int((time.monotonic() - started) * 1000), "table": None}


VM = ["-Xmx128m", "-XX:MaxMetaspaceSize=48m", "-XX:CompressedClassSpaceSize=16m",
      "-XX:ReservedCodeCacheSize=16m", "-XX:ActiveProcessorCount=1", "-XX:+UseSerialGC", "-Xss256k"]


def publish(job, result):
    if not job.is_dir():
        return
    pending = job / "response.tmp"
    pending.write_text(json.dumps(result, ensure_ascii=False))
    pending.replace(job / "response.json")


def process_job(directory, uid):
    job = Path(directory)
    workspace = None
    result = {"unavailable": True}
    try:
        request = json.loads((job / "claimed.json").read_text())
        language, code, inputs = request["language"], request["code"], request["inputs"]
        if language not in ("JAVA", "JAVASCRIPT", "PYTHON") or len(code.encode()) > 10000 or not 1 <= len(inputs) <= 20:
            raise ValueError("invalid-job")
        if any(not isinstance(value, str) or len(value.encode()) > 10000 for value in inputs):
            raise ValueError("invalid-input")
        limits = request["limits"]
        os.umask(0o077)
        try:
            user(uid)
            workspace = Path(tempfile.mkdtemp(prefix="exam-", dir="/tmp"))
            filename = {"JAVA": "Main.java", "JAVASCRIPT": "main.js", "PYTHON": "main.py"}[language]
            (workspace / filename).write_text(code)
        finally:
            owner()
        compile_result = None
        if language == "JAVA":
            compile_result = execute(["javac"] + ["-J" + value for value in VM] + ["--release", "21", "Main.java"],
                                     workspace, uid, "", seconds(limits.get("compileTimeout"), 10, 10), limits, job)
        if compile_result and not compile_result["completed"]:
            compile_result["error"] = "COMPILE" if compile_result["error"] == "RUNTIME" else compile_result["error"]
            compile_result["output"] = ""
            result = {"results": [compile_result for _ in inputs]}
        else:
            args = {"JAVA": ["java"] + VM + ["-cp", ".", "Main"],
                    "JAVASCRIPT": ["node", "--max-old-space-size=128", "main.js"],
                    "PYTHON": ["python", "-I", "-B", "main.py"]}[language]
            result = {"results": [execute(args, workspace, uid, value,
                                           seconds(limits.get("caseTimeout"), 3, 3), limits, job) for value in inputs]}
    except Exception:
        result = {"unavailable": True}
    finally:
        try:
            kill_user(uid)
            clean_files(uid)
            (job / "claimed.json").unlink(missing_ok=True)
            publish(job, result)
        except Exception:
            pass


def main():
    boot()
    print("exam-runner-ready uid=10001", flush=True)
    pending = {}
    with concurrent.futures.ProcessPoolExecutor(max_workers=2, mp_context=multiprocessing.get_context("fork")) as pool:
        while True:
            for future in list(pending):
                if future.done():
                    future.result()
                    del pending[future]
            available = set((0, 1)) - set(pending.values())
            for job in JOBS.iterdir():
                try:
                    if job.is_symlink() or not job.is_dir() or str(uuid.UUID(job.name)) != job.name:
                        continue
                    request = job / "request.json"
                    if not request.is_file() or request.is_symlink():
                        continue
                    if request.stat().st_size > 512000 or time.time() - request.stat().st_mtime > 15:
                        request.unlink(missing_ok=True)
                        publish(job, {"unavailable": True})
                        continue
                    if available:
                        slot = available.pop()
                        request.replace(job / "claimed.json")
                        pending[pool.submit(process_job, str(job), 20000 + slot)] = slot
                except (OSError, ValueError):
                    continue
            time.sleep(0.025)


if __name__ == "__main__":
    main()
