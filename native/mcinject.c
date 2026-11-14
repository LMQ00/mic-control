/* mcinject：把载荷装进系统声音进程。
 *
 * 用法：
 *   mcinject on  <载荷路径> <目标应用uid>
 *   mcinject off <载荷路径>
 *   mcinject status <载荷路径>
 *
 * 做三件事：
 *   1. 找到系统声音进程（audioserver）里两个库的基址；
 *   2. 用 ptrace 把载荷库加载进该进程，调它的 mc_install 装跳板；
 *   3. 设定目标应用与开关。
 *
 * 卸载只把两处入口的原始字节写回去，不重启进程，声音不断。
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <elf.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/uio.h>

#define PROC_NAME "audioserver"
#define LIB_FLINGER "libaudioflinger.so"
#define LIB_HAL     "libaudiohal@aidl.so"

struct regs { uint64_t x[31], sp, pc, pstate; };

static pid_t g_pid;
static int   g_fd = -1;
static struct regs g_base;
static uint64_t g_brk;

static uint64_t ut(uint64_t p) { return p & 0x00FFFFFFFFFFFFFFULL; }
static int mw(uint64_t a, const void *b, size_t n) {
    return pwrite(g_fd, b, n, (off_t)ut(a)) == (ssize_t)n ? 0 : -1;
}
static int mr(uint64_t a, void *b, size_t n) {
    return pread(g_fd, b, n, (off_t)ut(a)) == (ssize_t)n ? 0 : -1;
}

static pid_t find_proc(const char *name) {
    FILE *f = popen("pidof " PROC_NAME " 2>/dev/null", "r");
    if (f) {
        char buf[256] = {0};
        if (fgets(buf, sizeof buf, f)) {
            pid_t p = (pid_t)atoi(buf);
            pclose(f);
            if (p > 0) return p;
        }
        pclose(f);
    }
    (void)name;
    return -1;
}

static int maps_find(const char *needle, uint64_t *base, char *path, size_t pn) {
    char mp[64];
    snprintf(mp, sizeof mp, "/proc/%d/maps", g_pid);
    FILE *f = fopen(mp, "r");
    if (!f) return -1;
    char line[1024];
    int rc = -1;
    while (fgets(line, sizeof line, f)) {
        if (!strstr(line, needle)) continue;
        if (!strstr(line, " r--p 00000000") && !strstr(line, " r-xp ")) continue;
        if (base) sscanf(line, "%lx-", base);
        if (path) {
            char *sl = strstr(line, "/");
            if (sl) {
                char *nl = strchr(sl, '\n');
                if (nl) *nl = 0;
                snprintf(path, pn, "%s", sl);
            }
        }
        rc = 0;
        break;
    }
    fclose(f);
    return rc;
}

static uint64_t esym(const char *path, const char *name) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;
    off_t sz = lseek(fd, 0, SEEK_END);
    lseek(fd, 0, SEEK_SET);
    if (sz <= 0 || sz > 256 * 1024 * 1024) { close(fd); return 0; }
    void *m = malloc(sz);
    if (!m) { close(fd); return 0; }
    if (read(fd, m, sz) != sz) { free(m); close(fd); return 0; }
    close(fd);
    Elf64_Ehdr *eh = m;
    uint64_t out = 0;
    if (!memcmp(eh->e_ident, ELFMAG, 4)) {
        Elf64_Shdr *sh = (void *)((char *)m + eh->e_shoff);
        for (int i = 0; i < eh->e_shnum && !out; i++) {
            if (sh[i].sh_type != SHT_DYNSYM) continue;
            Elf64_Sym *sy = (void *)((char *)m + sh[i].sh_offset);
            char *st = (char *)m + sh[sh[i].sh_link].sh_offset;
            for (size_t j = 0; j < sh[i].sh_size / sizeof(Elf64_Sym); j++) {
                if (!sy[j].st_name || sy[j].st_shndx == SHN_UNDEF) continue;
                if (!strcmp(st + sy[j].st_name, name)) { out = sy[j].st_value; break; }
            }
        }
    }
    free(m);
    return out;
}

static uint64_t find_brk(void) {
    char mp[64];
    snprintf(mp, sizeof mp, "/proc/%d/maps", g_pid);
    FILE *f = fopen(mp, "r");
    if (!f) return 0;
    char line[1024];
    uint64_t res = 0;
    while (fgets(line, sizeof line, f) && !res) {
        if (!strstr(line, "bionic/libc.so") || !strstr(line, " r-xp ")) continue;
        uint64_t s, e;
        sscanf(line, "%lx-%lx", &s, &e);
        unsigned char buf[65536];
        for (uint64_t off = s; off < e && !res; off += sizeof buf) {
            size_t n = sizeof buf;
            if (off + n > e) n = e - off;
            if (pread(g_fd, buf, n, (off_t)off) != (ssize_t)n) break;
            for (size_t i = 0; i + 4 <= n; i += 4) {
                uint32_t v = buf[i] | (buf[i+1] << 8) | (buf[i+2] << 16) | ((uint32_t)buf[i+3] << 24);
                if ((v & 0xFFE0001Fu) == 0xD4200000u) { res = off + i; break; }
            }
        }
    }
    fclose(f);
    return res;
}

static int rg(struct regs *r) {
    struct iovec i = { r, sizeof *r };
    return ptrace(PTRACE_GETREGSET, g_pid, (void *)1, &i);
}
static int rs(struct regs *r) {
    struct iovec i = { r, sizeof *r };
    return ptrace(PTRACE_SETREGSET, g_pid, (void *)1, &i);
}

static uint64_t call2(uint64_t fn, uint64_t a, uint64_t b) {
    struct regs r = g_base;
    r.pc = fn; r.x[30] = g_brk; r.x[0] = a; r.x[1] = b;
    if (rs(&r)) return ~0ULL;
    ptrace(PTRACE_CONT, g_pid, 0, 0);
    int st;
    if (waitpid(g_pid, &st, 0) < 0) return ~0ULL;
    if (!WIFSTOPPED(st)) { fprintf(stderr, "目标进程在调用期间退出\n"); exit(20); }
    struct regs o;
    rg(&o);
    uint64_t ret = o.x[0];
    rs(&g_base);
    return ret;
}

static uint64_t call4(uint64_t fn, uint64_t a, uint64_t b, uint64_t c, uint64_t d) {
    struct regs r = g_base;
    r.pc = fn; r.x[30] = g_brk;
    r.x[0] = a; r.x[1] = b; r.x[2] = c; r.x[3] = d;
    if (rs(&r)) return ~0ULL;
    ptrace(PTRACE_CONT, g_pid, 0, 0);
    int st;
    if (waitpid(g_pid, &st, 0) < 0) return ~0ULL;
    if (!WIFSTOPPED(st)) { fprintf(stderr, "目标进程在调用期间退出\n"); exit(21); }
    struct regs o;
    rg(&o);
    uint64_t ret = o.x[0];
    rs(&g_base);
    return ret;
}

/* 装好之后拿到载荷在目标进程里的符号地址 */
static uint64_t psym(const char *payload_path, const char *name, char *out_path, size_t pn) {
    const char *bn = payload_path;
    for (const char *p = payload_path; *p; p++) if (*p == '/') bn = p + 1;
    uint64_t pb = 0;
    char pp[512] = {0};
    for (int t = 0; t < 100; t++) {
        if (maps_find(bn, &pb, pp, sizeof pp) == 0) break;
        usleep(50000);
    }
    if (!pb) return 0;
    if (out_path) snprintf(out_path, pn, "%s", pp);
    uint64_t off = esym(pp, name);
    return off ? pb + off : 0;
}

static int do_attach(void) {
    char mp[64];
    snprintf(mp, sizeof mp, "/proc/%d/mem", g_pid);
    g_fd = open(mp, O_RDWR);
    if (g_fd < 0) { perror("打开内存失败"); return 1; }
    if (ptrace(PTRACE_ATTACH, g_pid, 0, 0)) { perror("附加失败"); return 1; }
    int st;
    waitpid(g_pid, &st, 0);
    rg(&g_base);
    g_brk = find_brk();
    if (!g_brk) { fprintf(stderr, "找不到调用落点\n"); return 1; }
    return 0;
}

static void do_detach(void) {
    rs(&g_base);
    ptrace(PTRACE_DETACH, g_pid, 0, 0);
    if (g_fd >= 0) close(g_fd);
}

/* 把载荷加载进目标进程，返回载荷库在内存里的路径 */
static int load_payload(const char *payload, char *pp, size_t pn) {
    uint64_t cb = 0;
    char cp[512] = {0};
    if (maps_find("bionic/libc.so", &cb, cp, sizeof cp)) { fprintf(stderr, "目标进程里找不到 libc\n"); return 1; }
    uint64_t db = 0;
    char dp[512] = {0};
    maps_find("bionic/libdl.so", &db, dp, sizeof dp);

    uint64_t o_malloc = esym(cp, "malloc");
    uint64_t o_dlopen = esym(dp, "dlopen");
    uint64_t o_dlerror = esym(dp, "dlerror");
    if (!o_malloc || !o_dlopen) { fprintf(stderr, "取不到加载函数地址\n"); return 1; }

    uint64_t buf = call2(cb + o_malloc, 4096, 0);
    mw(buf, payload, strlen(payload) + 1);
    uint64_t h = call2(db + o_dlopen, buf, 2);
    if (!h) {
        uint64_t e = call2(db + o_dlerror, 0, 0);
        char m[400] = {0};
        if (e) mr(e, m, 399);
        fprintf(stderr, "加载载荷失败：%s\n", m);
        return 1;
    }
    const char *bn = payload;
    for (const char *p = payload; *p; p++) if (*p == '/') bn = p + 1;
    uint64_t pb = 0;
    for (int t = 0; t < 100; t++) {
        if (maps_find(bn, &pb, pp, pn) == 0) break;
        usleep(50000);
    }
    if (!pb) { fprintf(stderr, "载荷没有出现在目标进程内存里\n"); return 1; }
    return 0;
}

static int already_loaded(const char *payload) {
    const char *bn = payload;
    for (const char *p = payload; *p; p++) if (*p == '/') bn = p + 1;
    uint64_t pb = 0;
    char pp[512] = {0};
    return maps_find(bn, &pb, pp, sizeof pp) == 0;
}

static int cmd_on(const char *payload, uint64_t target) {
    g_pid = find_proc(NULL);
    if (g_pid <= 0) { fprintf(stderr, "系统声音进程没在跑\n"); return 1; }

    uint64_t af = 0, hal = 0;
    char tp[512] = {0};
    if (maps_find(LIB_FLINGER, &af, tp, sizeof tp)) { fprintf(stderr, "声音进程里找不到 " LIB_FLINGER "\n"); return 1; }
    if (maps_find(LIB_HAL, &hal, tp, sizeof tp)) { fprintf(stderr, "声音进程里找不到 " LIB_HAL "\n"); return 1; }

    if (do_attach()) { do_detach(); return 1; }

    /* 已经装过就先卸掉，保证重复执行结果一致 */
    char pp[512] = {0};
    if (already_loaded(payload)) {
        uint64_t un = psym(payload, "mc_uninstall", pp, sizeof pp);
        if (un) call2(un, 0, 0);
    } else {
        if (load_payload(payload, pp, sizeof pp)) { do_detach(); return 1; }
    }

    uint64_t f_install = psym(payload, "mc_install", pp, sizeof pp);
    uint64_t f_hcap = psym(payload, "mc_h_cap", pp, sizeof pp);
    uint64_t f_hinj = psym(payload, "mc_h_inj", pp, sizeof pp);
    uint64_t f_target = psym(payload, "mc_set_target", pp, sizeof pp);
    uint64_t f_on = psym(payload, "mc_on", pp, sizeof pp);
    if (!f_install || !f_hcap || !f_hinj || !f_target || !f_on) {
        fprintf(stderr, "载荷里缺少需要的符号\n");
        do_detach();
        return 1;
    }

    uint64_t hcap = call2(f_hcap, 0, 0);
    uint64_t hinj = call2(f_hinj, 0, 0);
    uint64_t rc = call4(f_install, af, hal, hcap, hinj);
    uint64_t f_diag = psym(payload, "mc_diag", pp, sizeof pp);
    if (f_diag) {
        fprintf(stderr, "诊断：fd=%lld 影子=%#llx 影首=%#llx 入首=%#llx\n",
            (long long)call2(f_diag, 0, 0), (unsigned long long)call2(f_diag, 1, 0),
            (unsigned long long)call2(f_diag, 2, 0), (unsigned long long)call2(f_diag, 3, 0));
    }
    if ((long)rc != 0) { fprintf(stderr, "装跳板失败：%ld\n", (long)rc); do_detach(); return 1; }
    call2(f_target, target, 0);
    call2(f_on, 0, 0);

    /* 装完立刻核对两处入口的字节，确认真的改上了 */
    uint64_t v = 0;
    if (mr(af + 0x1a4c14, &v, 8) || (v & 0xFFFFFFFFu) != 0xd50324dfu) {
        fprintf(stderr, "采集入口没有改上\n");
        do_detach();
        return 1;
    }
    v = 0;
    if (mr(hal + 0x168b34, &v, 8) || (v & 0xFFFFFFFFu) != 0x58000050u) {
        fprintf(stderr, "注入入口没有改上\n");
        do_detach();
        return 1;
    }

    do_detach();
    printf("已开启 目标=%llu 进程=%d\n", (unsigned long long)target, (int)g_pid);
    return 0;
}

static int cmd_off(const char *payload) {
    g_pid = find_proc(NULL);
    if (g_pid <= 0) { printf("系统声音进程没在跑，无需操作\n"); return 0; }
    if (!already_loaded(payload)) { printf("没有装过\n"); return 0; }
    if (do_attach()) { do_detach(); return 1; }
    uint64_t un = psym(payload, "mc_uninstall", NULL, 0);
    if (!un) { fprintf(stderr, "载荷里缺少卸载函数\n"); do_detach(); return 1; }
    call2(un, 0, 0);
    do_detach();
    printf("已关闭\n");
    return 0;
}

static int cmd_st(const char *payload) {
    g_pid = find_proc(NULL);
    if (g_pid <= 0) { fprintf(stderr, "声音服务没在跑\n"); return 1; }
    if (!already_loaded(payload)) { fprintf(stderr, "未启用\n"); return 1; }
    if (do_attach()) { do_detach(); return 1; }
    uint64_t f_st = psym(payload, "mc_st", NULL, 0);
    uint64_t f_stn = psym(payload, "mc_stn", NULL, 0);
    uint64_t f_cnt = psym(payload, "mc_stcnt", NULL, 0);
    uint64_t n = f_stn ? call2(f_stn, 0, 0) : 0;
    printf("流数=%llu\n", (unsigned long long)n);
    for (uint64_t i = 0; i < n && i < 12; i++) {
        uint64_t self = call2(f_st, i, 0);
        uint64_t rp   = call2(f_st, i, 1);
        uint64_t rate = call2(f_st, i, 3);
        uint64_t ch   = call2(f_st, i, 4);
        uint64_t cnt  = f_cnt ? call2(f_cnt, i, 0) : 0;
        uint64_t f_step = psym(payload, "mc_step", NULL, 0);
        uint64_t step = f_step ? call2(f_step, i, 0) : 0;
        printf("  [%llu] self=%#llx 速率=%llu 步长=%.4f 声道=%llu 次数=%llu 读=%llu\n",
               (unsigned long long)i, (unsigned long long)self,
               (unsigned long long)rate, step / 65536.0,
               (unsigned long long)ch, (unsigned long long)cnt, (unsigned long long)rp);
    }
    do_detach();
    return 0;
}

/* 往环形缓冲填一段已知频率的正弦，用于自检 */
static int cmd_sine(const char *payload, uint64_t freq, uint64_t frames, uint64_t rate) {
    g_pid = find_proc(NULL);
    if (g_pid <= 0) { fprintf(stderr, "声音服务没在跑\n"); return 1; }
    if (!already_loaded(payload)) { fprintf(stderr, "未启用\n"); return 1; }
    if (do_attach()) { do_detach(); return 1; }
    uint64_t f_fill = psym(payload, "mc_fillsine", NULL, 0);
    uint64_t f_on = psym(payload, "mc_on", NULL, 0);
    uint64_t f_cr = psym(payload, "mc_setcaprate", NULL, 0);
    if (!f_fill) { fprintf(stderr, "载荷里缺少填充函数\n"); do_detach(); return 1; }
    if (f_cr && rate) call2(f_cr, rate, 0);
    uint64_t rc = call2(f_fill, freq, frames);
    if (f_on) call2(f_on, 0, 0);
    do_detach();
    printf("已填入 %llu 帧 %llu 赫兹正弦\n",
           (unsigned long long)frames, (unsigned long long)freq);
    return rc ? 1 : 0;
}

/* 两处入口是否还是我们写的跳转字节 */
static int hooks_present(void) {
    uint64_t af = 0, hal = 0;
    char tp[512] = {0};
    if (maps_find(LIB_FLINGER, &af, tp, sizeof tp)) return 0;
    if (maps_find(LIB_HAL, &hal, tp, sizeof tp)) return 0;
    uint64_t v = 0;
    if (mr(af + 0x1a4c14, &v, 8)) return 0;
    if ((v & 0xFFFFFFFFu) != 0xd50324dfu) return 0;
    v = 0;
    if (mr(hal + 0x168b34, &v, 8)) return 0;
    if ((v & 0xFFFFFFFFu) != 0x58000050u) return 0;
    return 1;
}

static int cmd_status(const char *payload) {
    g_pid = find_proc(NULL);
    if (g_pid <= 0) { printf("未启用（声音服务没在跑）\n"); return 0; }
    if (do_attach()) { do_detach(); return 1; }
    int on = hooks_present();
    uint64_t cap = 0, sub = 0, uid = 0;
    if (on && already_loaded(payload)) {
        uint64_t f_cap = psym(payload, "mc_capt", NULL, 0);
        uint64_t f_sub = psym(payload, "mc_subs", NULL, 0);
        uint64_t f_uid = psym(payload, "mc_uidn", NULL, 0);
        if (f_cap) cap = call2(f_cap, 0, 0);
        if (f_sub) sub = call2(f_sub, 0, 0);
        if (f_uid) uid = call2(f_uid, 0, 0);
    }
    {
        uint64_t f_rate = psym(payload, "mc_rate", NULL, 0);
        uint64_t f_lb = psym(payload, "mc_last", NULL, 0);
        uint64_t f_lf = f_lb, f_lfs = f_lb;
        uint64_t f_av = psym(payload, "mc_avail", NULL, 0);
        uint64_t f_wf2 = psym(payload, "mc_wf", NULL, 0);
        uint64_t rate = f_rate ? call2(f_rate, 0, 0) : 0;
        uint64_t lb = f_lb ? call2(f_lb, 0, 0) : 0;
        uint64_t lf = f_lf ? call2(f_lf, 1, 0) : 0;
        uint64_t lfs = f_lfs ? call2(f_lfs, 2, 0) : 0;
        uint64_t av = f_av ? call2(f_av, 0, 0) : 0;
        uint64_t wf = f_wf2 ? call2(f_wf2, 0, 0) : 0;
        uint64_t f_cr = psym(payload, "mc_caprate", NULL, 0);
        uint64_t cr = f_cr ? call2(f_cr, 0, 0) : 0;

        printf("速率=%llu 缓冲速率=%llu 字节=%llu 帧=%llu 帧字节=%llu 可用=%llu 写=%llu\n",
               (unsigned long long)rate, (unsigned long long)cr, (unsigned long long)lb,
               (unsigned long long)lf, (unsigned long long)lfs,
               (unsigned long long)av, (unsigned long long)wf);
    }
    do_detach();
    if (!on) { printf("未启用\n"); return 0; }
    printf("启用中 采集=%llu 注入=%llu 音轨=%llu\n",
           (unsigned long long)cap, (unsigned long long)sub, (unsigned long long)uid);
    return 0;
}

/* 把环形缓冲的一段取出来，按十进制逐行打印 */
static int cmd_dump(const char *payload, uint64_t start, uint64_t n) {
    g_pid = find_proc(NULL);
    if (g_pid <= 0) { fprintf(stderr, "声音服务没在跑\n"); return 1; }
    if (!already_loaded(payload)) { fprintf(stderr, "未启用\n"); return 1; }
    if (do_attach()) { do_detach(); return 1; }

    uint64_t cb = 0; char cp[512] = {0};
    if (maps_find("bionic/libc.so", &cb, cp, sizeof cp)) { do_detach(); return 1; }
    uint64_t o_malloc = esym(cp, "malloc");
    if (!o_malloc) { do_detach(); return 1; }
    uint64_t buf = call2(cb + o_malloc, 65536, 0);

    uint64_t f_dump = psym(payload, "mc_dump", NULL, 0);
    uint64_t f_rp = psym(payload, "mc_srcrp", NULL, 0);
    uint64_t f_wf = psym(payload, "mc_wf", NULL, 0);
    if (!f_dump) { fprintf(stderr, "载荷里缺少取数据函数\n"); do_detach(); return 1; }

    uint64_t rp = f_rp ? call2(f_rp, 0, 0) : 0;
    uint64_t wf = f_wf ? call2(f_wf, 0, 0) : 0;
    fprintf(stderr, "写位置=%llu 读位置=%llu\n",
            (unsigned long long)wf, (unsigned long long)rp);

    uint64_t got = call4(f_dump, start, n, buf, 0);
    if (!got) { fprintf(stderr, "没有数据\n"); do_detach(); return 1; }
    int16_t *v = malloc(got * 2);
    mr(buf, v, got * 2);
    do_detach();
    for (uint64_t i = 0; i < got; i++) printf("%d\n", v[i]);
    return 0;
}

/* 把采集到的原始缓冲前若干字节按十六进制打印 */
static int cmd_raw(const char *payload, uint64_t n) {
    g_pid = find_proc(NULL);
    if (g_pid <= 0) { fprintf(stderr, "声音服务没在跑\n"); return 1; }
    if (!already_loaded(payload)) { fprintf(stderr, "未启用\n"); return 1; }
    if (do_attach()) { do_detach(); return 1; }
    uint64_t cb = 0; char cp[512] = {0};
    if (maps_find("bionic/libc.so", &cb, cp, sizeof cp)) { do_detach(); return 1; }
    uint64_t o_malloc = esym(cp, "malloc");
    uint64_t buf = call2(cb + o_malloc, 8192, 0);
    uint64_t f = psym(payload, "mc_rawdump", NULL, 0);
    if (!f) { fprintf(stderr, "缺少导出函数\n"); do_detach(); return 1; }
    uint64_t got = call2(f, buf, n);
    if (!got) { fprintf(stderr, "没有数据\n"); do_detach(); return 1; }
    unsigned char *v = malloc(got);
    mr(buf, v, got);
    do_detach();
    for (uint64_t i = 0; i < got; i++) printf("%02x%s", v[i], (i % 16 == 15) ? "\n" : " ");
    printf("\n");
    return 0;
}

int main(int argc, char **argv) {
    alarm(240);
    if (argc < 3) {
        fprintf(stderr,
            "用法：\n"
            "  %s on <载荷路径> <目标uid>\n"
            "  %s off <载荷路径>\n"
            "  %s status <载荷路径>\n"
            "  %s dump <载荷路径> <起始帧> <帧数>\n"
            "  %s sine <载荷路径> <频率> <帧数> <缓冲速率>\n", argv[0], argv[0], argv[0], argv[0], argv[0]);
        return 1;
    }
    const char *cmd = argv[1];
    const char *payload = argv[2];
    if (!strcmp(cmd, "on")) {
        uint64_t target = argc > 3 ? strtoull(argv[3], NULL, 10) : 0;
        return cmd_on(payload, target);
    }
    if (!strcmp(cmd, "off")) return cmd_off(payload);
    if (!strcmp(cmd, "status")) return cmd_status(payload);
    if (!strcmp(cmd, "st")) return cmd_st(payload);
    if (!strcmp(cmd, "sine")) {
        return cmd_sine(payload, argc > 3 ? strtoull(argv[3], 0, 0) : 440,
                        argc > 4 ? strtoull(argv[4], 0, 0) : 480000,
                        argc > 5 ? strtoull(argv[5], 0, 0) : 0);
    }
    if (!strcmp(cmd, "raw")) {
        uint64_t n = argc > 3 ? strtoull(argv[3], NULL, 0) : 256;
        return cmd_raw(payload, n);
    }
    if (!strcmp(cmd, "dump")) {
        uint64_t start = argc > 3 ? strtoull(argv[3], NULL, 10) : 0;
        uint64_t n = argc > 4 ? strtoull(argv[4], NULL, 10) : 4096;
        return cmd_dump(payload, start, n);
    }
    fprintf(stderr, "未知命令：%s\n", cmd);
    return 1;
}
