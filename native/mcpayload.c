/* mcpayload：系统声音服务里的两个钩子。
 *
 * 采集 libaudioflinger.so + 0x1a4c14   Track::getNextBuffer
 * 注入 libaudiohal@aidl.so   + 0x168b30   StreamInHalAidl::read
 *
 * 两个入口各写一条固定跳转，装好之后不再改动。处理器调用本库里的影子入口，
 * 影子入口执行原函数开头被覆盖的那几条指令，然后跳回原函数继续。
 * 声音通路上不产生任何系统调用。
 *
 * 环形缓冲按 48000 赫兹立体声存 16 位整数。每条录音流各有一个读位置。
 */
typedef unsigned long u64; typedef long i64; typedef unsigned int u32;
typedef unsigned char u8; typedef signed short s16; typedef unsigned short u16; typedef signed int i32;

#define CAP_OFF 0x1a4c14UL
#define INJ_OFF 0x168b30UL
#define CAPCOPY 24              /* 采集入口覆盖的字节数（跳板 20 + 落地点 4） */
#define INJCOPY 20              /* 注入入口从 entry+4 起覆盖的字节数（跳板 16 + 落地点 4） */
#define CAPN 6                  /* 影子入口重放的指令条数 */
#define INJN 5
#define RING_FRAMES (1024u*512u)
#define MAX_STREAMS 12
#define MAX_BACKLOG 9600u       /* 最多积压约 200 毫秒 */

static inline i64 sy(i64 n,i64 a,i64 b,i64 c,i64 d,i64 e,i64 f){
    register i64 x8 __asm__("x8")=n; register i64 x0 __asm__("x0")=a;
    register i64 x1 __asm__("x1")=b; register i64 x2 __asm__("x2")=c;
    register i64 x3 __asm__("x3")=d; register i64 x4 __asm__("x4")=e;
    register i64 x5 __asm__("x5")=f;
    __asm__ volatile("svc #0":"+r"(x0):"r"(x8),"r"(x1),"r"(x2),"r"(x3),"r"(x4),"r"(x5):"memory");
    return x0;
}
#define SYS_openat   56
#define SYS_pwrite64 68
#define AT_FDCWD (-100)

/* 影子入口：64 字节对齐，放在 .text 里，保证可执行 */
__asm__(
".text\n"
".local mc_shcap\n.type mc_shcap, %function\n.p2align 6\n"
"mc_shcap:\n  .space 96, 0\n"
".size mc_shcap, 96\n"
".local mc_shinj\n.type mc_shinj, %function\n.p2align 6\n"
"mc_shinj:\n  .space 96, 0\n"
".size mc_shinj, 96\n"
);
extern u8 mc_shcap[];
extern u8 mc_shinj[];

static u64 addr_shcap(void){
    u64 a;
    __asm__ volatile("adrp %0, mc_shcap\n\tadd %0, %0, :lo12:mc_shcap" : "=r"(a));
    return a;
}
static u64 addr_shinj(void){
    u64 a;
    __asm__ volatile("adrp %0, mc_shinj\n\tadd %0, %0, :lo12:mc_shinj" : "=r"(a));
    return a;
}

/* 复制原函数开头若干条指令到影子入口。
   adr/adrp 这类按位置算地址的指令要重新编码，否则搬到别处会指向错误目标。 */
static void reloc_fixup(u32 *dst, u64 dst_addr, const u32 *src, u64 src_addr, int n){
    for (int i = 0; i < n; i++){
        u32 ins = src[i];
        u64 d = dst_addr + i*4, s = src_addr + i*4;
        if ((ins & 0x9F000000u) == 0x10000000u){          /* adr */
            u64 imm = ((ins >> 5) & 0x7FFFF) << 2 | ((ins >> 29) & 3);
            if (ins & 0x800000u) imm |= ~((1ULL << 21) - 1);
            i64 aim = (i64)s + (i64)imm;
            i64 d2 = aim - (i64)d;
            u32 v = ins & 0x9F00001Fu;
            v |= ((u32)(d2 & 3)) << 29;
            v |= ((u32)((d2 >> 2) & 0x7FFFF)) << 5;
            dst[i] = v;
        } else if ((ins & 0x9F000000u) == 0x90000000u){    /* adrp */
            u64 imm = ((ins >> 5) & 0x7FFFF) << 14 | ((ins >> 29) & 3) << 12;
            if (ins & 0x800000u) imm |= ~((1ULL << 33) - 1);
            i64 aim = ((i64)s & ~0xFFFULL) + (i64)imm;
            i64 d2 = aim - ((i64)d & ~0xFFFULL);
            u32 v = ins & 0x9F00001Fu;
            v |= ((u32)((d2 >> 12) & 3)) << 29;
            v |= ((u32)((d2 >> 14) & 0x7FFFF)) << 5;
            dst[i] = v;
        } else if ((ins & 0x7C000000u) == 0x14000000u){     /* b */
            i64 imm = (i64)(ins & 0x3FFFFFFu) << 2;
            if (ins & 0x2000000u) imm |= ~((1LL << 28) - 1);
            i64 d2 = ((i64)s + imm) - (i64)d;
            dst[i] = (ins & 0xFC000000u) | (u32)((d2 >> 2) & 0x3FFFFFF);
        } else if ((ins & 0x7E000000u) == 0x34000000u ||      /* cbz / cbnz */
                   (ins & 0xFF000010u) == 0x54000000u){      /* b.cond */
            i64 imm = (i64)((ins >> 5) & 0x7FFFFu) << 2;
            if (ins & 0x100000u) imm |= ~((1LL << 21) - 1);
            i64 d2 = ((i64)s + imm) - (i64)d;
            dst[i] = (ins & 0xFF00001Fu) | (u32)(((d2 >> 2) & 0x7FFFF) << 5);
        } else if ((ins & 0x7E000000u) == 0x36000000u){      /* tbz / tbnz */
            i64 imm = (i64)((ins >> 5) & 0x3FFFu) << 2;
            if (ins & 0x4000u) imm |= ~((1LL << 16) - 1);
            i64 d2 = ((i64)s + imm) - (i64)d;
            dst[i] = (ins & 0xFFF8001Fu) | (u32)(((d2 >> 2) & 0x3FFF) << 5);
        } else {
            dst[i] = ins;
        }
    }
}

/* 用 adrp/add 取本库内符号地址，不经过 GOT */
static u64 addr_of_cap(void){
    u64 a;
    __asm__ volatile("adrp %0, mc_cap\n\tadd %0, %0, :lo12:mc_cap" : "=r"(a));
    return a;
}
static u64 addr_of_inj(void){
    u64 a;
    __asm__ volatile("adrp %0, mc_inj\n\tadd %0, %0, :lo12:mc_inj" : "=r"(a));
    return a;
}

__attribute__((visibility("hidden"))) static u64 g_af, g_hal;
__attribute__((visibility("hidden"))) static i64 g_mem = -1;
__attribute__((visibility("hidden"))) static u32 g_origok;

__attribute__((visibility("hidden"))) static u8 g_corig[CAPCOPY], g_cpatch[CAPCOPY];
__attribute__((visibility("hidden"))) static u8 g_iorig[INJCOPY], g_ipatch[INJCOPY];

__attribute__((visibility("hidden"))) static s16 g_ring[RING_FRAMES*2];
__attribute__((visibility("hidden"))) static u64 g_wf;
__attribute__((visibility("hidden"))) static volatile u64 g_target;
__attribute__((visibility("hidden"))) static volatile u32 g_on;
__attribute__((visibility("hidden"))) static u64 g_capc, g_capt, g_injc, g_subs;
__attribute__((visibility("hidden"))) static u64 g_ch, g_rate;
__attribute__((visibility("hidden"))) static volatile u32 g_locks;
static void lk(void){
    for (;;){
        u32 v, st;
        __asm__ volatile("ldaxr %w0, [%1]" : "=r"(v) : "r"(&g_locks) : "memory");
        if (v){ __asm__ volatile("yield" ::: "memory"); continue; }
        __asm__ volatile("stxr %w0, %w2, [%1]" : "=&r"(st) : "r"(&g_locks), "r"(1u) : "memory");
        if (!st) return;
    }
}
static void ul(void){ __asm__ volatile("stlr wzr, [%0]"::"r"(&g_locks):"memory"); }
/* 采集源：只从一条音轨抄 */
__attribute__((visibility("hidden"))) static volatile u64 g_src;
__attribute__((visibility("hidden"))) static u64 g_srctick;
__attribute__((visibility("hidden"))) static u64 g_capraw, g_capfc;
/* 最近一次产出的前 4096 个 16 位样本，供外部核对 */
__attribute__((visibility("hidden"))) static u64 g_lastbytes, g_lastframes, g_lastfsz;
/* 最后一次抄到声音的时刻。停止放歌后不再重播旧数据。 */
__attribute__((visibility("hidden"))) static volatile u64 g_lastwrite;
/* 环形缓冲的写入速率。各应用的播放采样率不同，这里量出来备用。 */
__attribute__((visibility("hidden"))) static volatile u64 g_caprate = 48000;
__attribute__((visibility("hidden"))) static u64 g_capt0, g_capf0;
__attribute__((visibility("hidden"))) static u64 g_capcand, g_capvote;
/* 新流从哪一帧开始读。0 表示按积压上限自动取，本身是一个有效值，所以存 +1。 */
__attribute__((visibility("hidden"))) static volatile u64 g_rpstart1;

/* 用相邻两次调用的单调时钟间隔反推流的实际采样率。
   采样率字段在所有流上读到的是同一个值，靠不住。 */
static inline u64 now_ns(void){
    u64 t;
    __asm__ volatile("isb; mrs %0, cntvct_el0" : "=r"(t));
    return t;
}
static inline u64 cnt_freq(void){
    u64 f;
    __asm__ volatile("mrs %0, cntfrq_el0" : "=r"(f));
    return f;
}
/* 两次读数之间过了多少纳秒。频率不是 10 的整数幂，
   先整数除会丢掉余数，时间就被算短了。 */
static inline u64 ns_diff(u64 a, u64 b){
    if (a <= b) return 0;
    u64 f = cnt_freq();
    return f ? (a - b) * 1000000000ULL / f : 0;
}

/* step：每输出一帧要从环形缓冲吃掉多少帧，16 位小数。
   fixed：这条流用指定起点，第一块不套积压钳位。 */
typedef struct { u64 self; u64 rp; u64 frac; u64 rate; u64 ch; u64 lastt; u64 cnt; i32 last;
                 u64 step; u64 fixed; } st_t;
__attribute__((visibility("hidden"))) static st_t g_st[MAX_STREAMS];
__attribute__((visibility("hidden"))) static u32 g_stn;

/* 新流从“最旧可用数据”开始读。缓冲里最多留 200 毫秒的积压，
   新流取这段的起点，既不会读空，也不会落后太多。 */
static void st_init(st_t *p, u64 self){
    u64 wf = g_wf;
    u64 start = g_rpstart1;
    p->self = self;
    p->rp = start ? start - 1 : (wf > MAX_BACKLOG ? wf - MAX_BACKLOG : 0);
    if (start) g_rpstart1 = 0;      /* 指定起点只给下一条新流用一次 */
    p->frac = 0;
    p->rate = 48000;
    p->ch = 2;
    p->lastt = 0;
    p->cnt = 0;
    p->last = 0;
    p->step = 0;
    p->fixed = start ? 1 : 0;
}

/* 流按对象地址认。地址会被后来的流复用：隔了 5 秒又出现的地址按新流重置。
   表满了只回收 5 秒没被调用的条目；没有可回收的就放弃这条流，不碰正在用的。 */
#define STALE_NS 5000000000ULL
static st_t* st_find(u64 self, u64 now){
    st_t *r = 0;
    lk();
    for (u32 i = 0; i < g_stn; i++) if (g_st[i].self == self){
        r = &g_st[i];
        if (r->lastt && ns_diff(now, r->lastt) > STALE_NS) st_init(r, self);
        break;
    }
    if (!r){
        if (g_stn < MAX_STREAMS) r = &g_st[g_stn++];
        else for (u32 i = 0; i < g_stn; i++)
            if (g_st[i].lastt && ns_diff(now, g_st[i].lastt) > STALE_NS &&
                (!r || g_st[i].lastt < r->lastt)) r = &g_st[i];
        if (r) st_init(r, self);
    }
    if (r) r->lastt = now;
    ul();
    return r;
}

static void flushc(u64 a, u64 n){
    u64 c; __asm__ volatile("mrs %0, ctr_el0":"=r"(c));
    u64 dl = 4UL << ((c >> 16) & 0xf), il = 4UL << (c & 0xf);
    for (u64 x = a & ~(dl-1); x < a+n; x += dl) __asm__ volatile("dc cvau, %0"::"r"(x):"memory");
    __asm__ volatile("dsb ish":::"memory");
    for (u64 x = a & ~(il-1); x < a+n; x += il) __asm__ volatile("ic ivau, %0"::"r"(x):"memory");
    __asm__ volatile("dsb ish; isb":::"memory");
}
static void memw(u64 a, const void *d, u64 n){
    if (g_mem < 0) g_mem = sy(SYS_openat, AT_FDCWD, (i64)"/proc/self/mem", 2, 0, 0, 0);
    if (g_mem >= 0) sy(SYS_pwrite64, g_mem, (i64)d, (i64)n, (i64)a, 0, 0);
    flushc(a, n);
}
/* 数声道掩码里有几位，得到声道数。 */
static inline u32 popc(u32 x){
    u32 c = 0;
    while (x){ x &= x - 1; c++; }
    return c;
}
/* 标准的采样率。字段读出的值不在这张表里，就不采信这个字段。 */
static const u64 g_stdrate[] = {4000,8000,11025,12000,16000,22050,24000,32000,
                                44100,48000,64000,88200,96000,176400,192000,384000};
static inline u32 is_std_rate(u64 r){
    for (u32 k = 0; k < sizeof(g_stdrate)/sizeof(g_stdrate[0]); k++)
        if (g_stdrate[k] == r) return 1;
    return 0;
}
static inline float u2f(const u8 *q){
    union { u32 u; float f; } cv;
    cv.u = (u32)q[0] | ((u32)q[1] << 8) | ((u32)q[2] << 16) | ((u32)q[3] << 24);
    return cv.f;
}
/* 前 nf 帧按浮点读的粗糙度（相邻样本差与幅度之比，放大一千倍）。
   不是正常浮点（NaN、超幅、全零）时返回 0。 */
#define FLSAMP 32
static inline u64 rough_float(const u8 *p, u64 ch, u64 nf){
    float acc = 0.0f, mag = 0.0f, prev = 0.0f;
    for (u64 i = 0; i < nf; i++){
        float f = u2f(p + i*ch*4);
        if (f != f || f > 4.0f || f < -4.0f) return 0;
        float d = f - prev;
        if (d < 0.0f) d = -d;
        acc += d;
        mag += (f < 0.0f) ? -f : f;
        prev = f;
    }
    if (!(mag > 0.0f)) return 0;
    return (u64)(acc * 1000.0f / mag);
}
/* 同样一段按 16 位整数读的粗糙度。全零时返回全 1。 */
static inline u64 rough_s16(const u8 *p, u64 ch, u64 nf){
    i64 acc = 0, mag = 0; i32 prev = 0;
    for (u64 i = 0; i < nf; i++){
        const u8 *q = p + i*ch*2;
        i32 v = (i32)(s16)(u16)((u16)q[0] | ((u16)q[1] << 8));
        i32 d = v - prev;
        acc += d < 0 ? -d : d;
        mag += v < 0 ? -v : v;
        prev = v;
    }
    if (!mag) return ~0ULL;
    return (u64)(acc * 1000 / mag);
}
/* 只认两种编码：1 是 16 位整数，5 是 32 位浮点。fsz 是每帧字节数。 */
static inline u64 fsz_of(u32 fmt, u64 ch){
    return (fmt == 5 ? 4 : 2) * ch;
}
/* 取第 c 个声道的样本，换算成 16 位整数。 */
static inline i32 csmp(const u8 *p, u32 fmt, u64 c){
    if (fmt == 5){
        float f = u2f(p + c*4);
        if (f != f) return 0;
        if (f > 1.0f) f = 1.0f; else if (f < -1.0f) f = -1.0f;
        return (i32)(f * 32767.0f);
    }
    const u8 *q = p + c*2;
    return (i32)(s16)(u16)((u16)q[0] | ((u16)q[1] << 8));
}
/* 浮点判别要读的帧数。块短于 128 帧时不做判别，整块不抄。 */
#define FLMINFRAMES 128
/* 采集：目标应用的播放复制进环形缓冲。 */
__attribute__((used, noinline))
i64 mc_cap_c(void *self, void *buf){
    i64 r = ((i64(*)(void*,void*))addr_shcap())(self, buf);
    g_capc++;
    if (!self || !buf || r != 0 || !g_on) return r;

    u64 uid = *(u32 *)((u8 *)self + 0x1b4);
    u64 t = g_target;
    if (t && uid != t) return r;

    /* 只从一条音轨抄：首选写入者当选，静默约 100 毫秒后换人 */
    u64 src = g_src;
    if (src != (u64)self){
        if (src && g_wf - g_srctick <= 4800) return r;
        g_src = (u64)self;
        g_srctick = g_wf;
    }

    /* 音轨对象里存着这条音轨的配置：0x178 采样率、0x17c 编码、
       0x180 声道掩码、0x184 声道数。四项对得上才采信，
       否则按 16 位整数立体声处理。 */
    u8 *pcm = *(u8 **)buf;
    u64 fc = *(u64 *)((u8 *)buf + 8);
    g_capraw = (u64)pcm;
    g_capfc = fc;
    if (!pcm || !fc) return r;

    u32 cfgr = *(u32 *)((u8 *)self + 0x178);
    u32 fmt = *(u32 *)((u8 *)self + 0x17c);
    u32 cfgn = *(u32 *)((u8 *)self + 0x180);
    u32 cfgc = *(u32 *)((u8 *)self + 0x184);
    u64 ch = popc(cfgn);
    if (ch < 1 || ch > 8 || cfgc != ch || !is_std_rate(cfgr) ||
        (fmt != 1 && fmt != 5)){
        ch = 2;
        fmt = 1;
    }

    /* 浮点要两件事都成立才采信：块够长，且按浮点读明显比按 16 位读平滑。
       块短于 128 帧时判别要读的字节数可能超出这块内存，直接不判。 */
    if (fmt == 5){
        if (fc < FLMINFRAMES) return r;
        u64 rf = rough_float(pcm, ch, FLSAMP);
        u64 rs = rough_s16(pcm, ch, FLSAMP);
        if (!rf || rs <= rf * 2) fmt = 1;
    }

    g_ch = ch;
    if (is_std_rate(cfgr)) g_rate = cfgr;
    if (fc > RING_FRAMES) fc = RING_FRAMES;

    u64 fsz = fsz_of(fmt, ch);
    u64 w = g_wf;
    for (u64 i = 0; i < fc; i++){
        const u8 *p = pcm + i*fsz;
        i32 l = csmp(p, fmt, 0);
        i32 rr = ch > 1 ? csmp(p, fmt, 1) : l;
        u64 q = (w + i) % RING_FRAMES;
        g_ring[q*2]   = (s16)l;
        g_ring[q*2+1] = (s16)rr;
    }
    __asm__ volatile("dmb ish":::"memory");
    g_wf = w + fc;
    g_srctick = g_wf;
    g_lastwrite = now_ns();
    g_capt++;

    /* 量写入速率：累计满 1 秒重算一次，吸附到标准采样率 */
    {
        u64 t = g_lastwrite;
        if (!g_capf0){ g_capf0 = g_wf; g_capt0 = t; }
        else if (ns_diff(t, g_capt0) > 1000000000ULL){
            u64 df = g_wf - g_capf0, dt = ns_diff(t, g_capt0);
            u64 est = df * 1000000000ULL / dt;
            if (est >= 8000 && est <= 192000){
                static const u64 sd[] = {8000,11025,12000,16000,22050,24000,
                                         32000,44100,48000,64000,88200,96000,192000};
                u64 best = 48000, bd = ~0ULL;
                for (u32 k = 0; k < sizeof(sd)/sizeof(sd[0]); k++){
                    u64 d3 = est > sd[k] ? est - sd[k] : sd[k] - est;
                    if (d3 < bd){ bd = d3; best = sd[k]; }
                }
                /* 连续三次得到同一个值才切换。
                   单次结果会在相邻两个标准值之间来回跳，步长跟着抖，声音跟着抖。 */
                if (bd * 100 <= best * 15){
                    if (best == g_capcand){ if (g_capvote < 3) g_capvote++; }
                    else { g_capcand = best; g_capvote = 0; }
                    if (g_capvote >= 3 && g_caprate != best) g_caprate = best;
                } else { g_capcand = 0; g_capvote = 0; }
            }
            g_capf0 = g_wf; g_capt0 = t;
        }
    }
    return r;
}

/* 注入：用环形缓冲覆盖 HAL 交付的录音数据。
   原函数开头含 PC 相对指令，不能搬到影子入口里重放，
   改回「调用前恢复原字节、返回后重写跳板」。 */
__attribute__((used, noinline))
i64 mc_inj_c(void *self, void *buf, u64 bytes, u64 *outN){
    i64 r = ((i64(*)(void*,void*,u64,u64*))addr_shinj())(self, buf, bytes, outN);
    g_injc++;
    if (r != 0 || !self || !buf || !bytes || !g_on) return r;

    /* 原函数写回的实际字节数；拿不到时按请求长度算。 */
    u64 got = bytes;
    if (outN && *outN < bytes) got = *outN;

    u64 t = now_ns();
    st_t *sp = st_find((u64)self, t);
    if (!sp) return r;

    /* 流对象里存着这条流的配置：0x44 采样率、0x48 声道掩码。
       声道数按掩码位数算，采样率直接用配置值。 */
    u32 cfgr = *(u32 *)((u8 *)self + 0x44);
    u32 cfgn = *(u32 *)((u8 *)self + 0x48);
    u64 ch = popc(cfgn);
    if (ch < 1 || ch > 8) ch = 2;
    sp->ch = ch;
    g_ch = ch;
    if (is_std_rate(cfgr)) sp->rate = cfgr;

    u64 fsz = 2 * ch;
    u64 frames = got / fsz;
    sp->lastt = t;
    sp->cnt++;
    if (!frames) return r;

    s16 *o = (s16 *)buf;
    u64 wf = g_wf;
    g_lastbytes = got; g_lastframes = frames; g_lastfsz = fsz;

    /* 不属于整帧的尾巴清零，不留原始麦克风数据 */
    for (u64 k = frames * ch; k < bytes / 2; k++) o[k] = 0;

    /* 还没抄到音乐：输出静音 */
    if (wf < 2){
        for (u64 k = 0; k < frames * ch; k++) o[k] = 0;
        if (outN) *outN = frames * fsz;
        g_subs++;
        return r;
    }

    /* 缓冲里已有的数据超过允许的积压：退到积压上限。
       读位置越过写位置（旧流复用对象留下的）就贴住写位置。 */
    if (sp->fixed){
        /* 指定起点一直有效，直到积压回到上限以内 */
        if (wf <= sp->rp || wf - sp->rp <= MAX_BACKLOG) sp->fixed = 0;
    } else if (wf > sp->rp && wf - sp->rp > MAX_BACKLOG)
        sp->rp = wf - MAX_BACKLOG;
    else if (sp->rp > wf)
        sp->rp = wf;

    /* 步长 = 环形缓冲的写入速率 ÷ 这条流的采样率。
       缓冲写入速率随歌曲采样率变化，每次重算。 */
    {
        u64 sr = sp->rate;
        sp->step = sr ? (g_caprate << 16) / sr : 0x10000;
    }

    u64 rate = sp->rate;
    g_rate = rate;
    u64 rp = sp->rp;
    u64 step = sp->step;
    u64 pos = sp->frac;
    i32 last = sp->last;

    u64 i = 0;
    for (; i < frames; i++){
        u64 src = pos >> 16;
        u64 fr = pos & 0xFFFF;
        i32 L, R;
        if (rp + src + 1 < wf){
            u64 p0 = (rp + src) % RING_FRAMES, p1 = (rp + src + 1) % RING_FRAMES;
            /* 相邻两点线性插值。直接取整会让波形出锯齿，听着发毛。 */
            i32 l0 = g_ring[p0*2], l1 = g_ring[p1*2];
            i32 r0 = g_ring[p0*2+1], r1 = g_ring[p1*2+1];
            L = l0 + (i32)(((i64)(l1 - l0) * (i64)fr) >> 16);
            R = r0 + (i32)(((i64)(r1 - r0) * (i64)fr) >> 16);
        } else if (rp + src < wf){
            u64 p = (rp + src) % RING_FRAMES;
            L = g_ring[p*2]; R = g_ring[p*2+1];
        } else {
            break;
        }
        last = (ch == 1) ? (L + R) / 2 : L;
        if (ch == 1) o[i] = (s16)last;
        else {
            o[i*ch] = (s16)L;
            o[i*ch+1] = (s16)R;
            for (u64 c = 2; c < ch; c++) o[i*ch+c] = (s16)((c & 1) ? R : L);
        }
        pos += step;
    }

    /* 缓冲里没数据了：这一段输出静音，读位置停在已有数据的末尾。
       数据再写进来时从这里接着播，读位置不会被这段静音甩到前面去。 */
    for (; i < frames; i++){
        if (ch == 1) o[i] = 0;
        else for (u64 c = 0; c < ch; c++) o[i*ch+c] = 0;
    }
    sp->frac = pos & 0xFFFF;
    sp->last = last;
    sp->rp = rp + (pos >> 16);
    if (sp->rp > wf) sp->rp = wf;
    if (outN) *outN = frames * fsz;
    g_subs++;
    return r;
}

__asm__(
".text\n.local mc_cap\n.type mc_cap, %function\n.p2align 4\n"
"mc_cap:\n  bti jc\n  b mc_cap_c\n.size mc_cap, .-mc_cap\n"
".local mc_inj\n.type mc_inj, %function\n.p2align 4\n"
"mc_inj:\n  bti jc\n  b mc_inj_c\n.size mc_inj, .-mc_inj\n"
);
extern i64 mc_cap(void*, void*);
extern i64 mc_inj(void*, void*, u64, u64*);

u64 mc_h_cap(void){ return addr_of_cap(); }
u64 mc_h_inj(void){ return addr_of_inj(); }
u64 mc_capc(void){ return g_capc; }
u64 mc_capt(void){ return g_capt; }
u64 mc_injc(void){ return g_injc; }
u64 mc_subs(void){ return g_subs; }
u64 mc_ch(void){ return g_ch; }
u64 mc_caprate(void){ return g_caprate; }
u64 mc_step(u64 i){ return i < g_stn ? g_st[i].step : 0; }
u64 mc_rate(void){ return g_rate; }
u64 mc_wf(void){ return g_wf; }
/* 把环形缓冲的一段原样读出来，供外部核对 */
u64 mc_ring(u64 i){ return (u64)(unsigned short)g_ring[i & (RING_FRAMES*2-1)]; }
/* 把环形缓冲的一段左声道抄给调用者，供外部核对；返回实际抄到的帧数 */
u64 mc_dump(u64 start, u64 n, void *out){
    s16 *o = (s16 *)out;
    u64 wf = g_wf;
    if (start >= wf) return 0;
    if (start + n > wf) n = wf - start;
    if (n > 32768) n = 32768;
    for (u64 i = 0; i < n; i++){
        u64 p = ((start + i) % RING_FRAMES) * 2;
        o[i] = g_ring[p];
    }
    return n;
}
u64 mc_srcrp(void){ return g_stn ? g_st[0].rp : 0; }
/* 读某条流的记录字段：0=self 1=rp 2=frac 3=rate 4=ch */
u64 mc_st(u64 i, u64 f){
    if (i >= g_stn) return 0;
    st_t *p = &g_st[i];
    switch (f) {
        case 0: return p->self;
        case 1: return p->rp;
        case 2: return p->frac;
        case 3: return p->rate;
        case 4: return p->ch;
        case 5: return (u64)sizeof(st_t);
        case 6: return p->lastt;
        case 7: return (u64)p->frac;
        default: return 0;
    }
}
/* 采集到的原始缓冲前若干字节，原样导出 */
u64 mc_rawdump(void *out, u64 n){
    if (!g_capraw || !n) return 0;
    if (n > 4096) n = 4096;
    u8 *o = (u8 *)out;
    u8 *p = (u8 *)g_capraw;
    for (u64 i = 0; i < n; i++) o[i] = p[i];
    return n;
}
u64 mc_capraw(void){ return g_capraw; }
u64 mc_capfc(void){ return g_capfc; }
u64 mc_last(u64 i){ return i==0?g_lastbytes:(i==1?g_lastframes:g_lastfsz); }
/* 每条流的自称采样率与实测采样率 */
u64 mc_strate(u64 i){ return i < g_stn ? g_st[i].rate : 0; }
u64 mc_stcnt(u64 i){ return i < g_stn ? g_st[i].cnt : 0; }
u64 mc_stn(void){ return g_stn; }
u64 mc_avail(void){ return g_stn ? (g_wf > g_st[0].rp ? g_wf - g_st[0].rp : 0) : 0; }
u64 mc_uidn(void){ return g_src ? 1 : 0; }
void mc_set_target(u64 u){ g_target = u; }
void mc_setcaprate(u64 r){ if (r >= 8000 && r <= 192000) g_caprate = r; }
void mc_setstart(u64 f){ g_rpstart1 = f + 1; }
/* 生成测试音时独立指定生成速率，不受测量值影响 */
/* 打开时只把已有流的读位置对齐到最新处，不清空流表。
   清空会让正在录音的流重新起步，声音会断一下。 */
void mc_on(void){
    __asm__ volatile("dmb ish":::"memory");
    u64 wf = g_wf;
    u64 t = now_ns();
    lk();
    for (u32 i = 0; i < g_stn; i++){ g_st[i].rp = wf; g_st[i].frac = 0; g_st[i].lastt = t; }
    ul();
    g_src = 0;
    g_srctick = 0;
    g_on = 1;
}
void mc_off(void){ g_on = 0; }
void mc_reset(void){
    __asm__ volatile("dmb ish":::"memory");
    u64 wf = g_wf;
    lk();
    for (u32 i = 0; i < g_stn; i++){ g_st[i].rp = wf; g_st[i].frac = 0; }
    ul();
    g_src = 0; g_srctick = 0;
    g_capc = g_capt = g_injc = g_subs = 0;
    g_capf0 = 0; g_capt0 = 0; g_capcand = 0; g_capvote = 0;
}

/* 在环形缓冲里放已知频率的正弦，用于验证取样换算 */
int mc_fillsine(u64 freq, u64 frames){
    if (frames > RING_FRAMES) frames = RING_FRAMES;
    static const s16 tbl[64] = {
        0,3212,6393,9512,12540,15447,18205,20788,23170,25330,27246,28899,
        30274,31357,32138,32610,32767,32610,32138,31357,30274,28899,27246,
        25330,23170,20788,18205,15447,12540,9512,6393,3212,0,-3212,-6393,
        -9512,-12540,-15447,-18205,-20788,-23170,-25330,-27246,-28899,-30274,
        -31357,-32138,-32610,-32767,-32610,-32138,-31357,-30274,-28899,-27246,
        -25330,-23170,-20788,-18205,-15447,-12540,-9512,-6393,-3212};
    /* 表里只有 64 个点，直接取整会让相邻帧落回同一个值。
       按 16 位小数在表内插值。 */
    u64 fr = g_caprate ? g_caprate : 48000u;
    u64 step = ((freq * 64u) << 16) / fr;
    u64 ph = 0;
    for (u64 i = 0; i < frames; i++){
        u64 idx = (ph >> 16) & 63u;
        u64 fr  = ph & 0xFFFFu;
        i32 a = tbl[idx], b = tbl[(idx + 1) & 63u];
        s16 v = (s16)(a + (i32)(((i64)(b - a) * (i64)fr) >> 16));
        ph += step;
        g_ring[i*2] = v; g_ring[i*2+1] = v;
    }
    __asm__ volatile("dmb ish":::"memory");
    lk(); g_wf = frames; g_stn = 0; g_rpstart1 = 1; ul();
    return 0;
}

int mc_fillpat(u64 v, u64 frames){
    if (frames > RING_FRAMES) frames = RING_FRAMES;
    for (u64 i = 0; i < frames; i++){ g_ring[i*2] = (s16)v; g_ring[i*2+1] = (s16)v; }
    __asm__ volatile("dmb ish":::"memory");
    lk(); g_wf = frames; g_stn = 0; ul();
    return 0;
}

/* 影子入口：原函数开头 n 条指令（含落地点那条），再 br 回原函数第 n+1 条。
   跳转落点必须是 bti 指令，所以覆盖区末尾补一条 bti c，
   被它占掉的那条原指令改到影子入口里重放。 */
static void mkshadow(u64 shadow, u64 base, const u32 *orig, int n){
    u32 bti = 0xd503245fU;      /* bti c */
    u32 ldr = 0x58000050U;      /* ldr x16, #8 */
    u32 br  = 0xd61f0200U;      /* br x16 */
    int slot = 4 + n*4;
    u8 buf[128];
    for (int i = 0; i < 128; i++) buf[i] = 0;
    for (int k = 0; k < 4; k++) buf[k] = (u8)(bti >> (8*k));
    reloc_fixup((u32 *)(buf + 4), shadow + 4, orig, base, n);
    for (int k = 0; k < 4; k++) buf[slot+k]   = (u8)(ldr >> (8*k));
    for (int k = 0; k < 4; k++) buf[slot+4+k] = (u8)(br  >> (8*k));
    u64 back = base + (u64)(n-1) * 4;    /* 落地点：覆盖区最后一条的地址 */
    for (int k = 0; k < 8; k++) buf[slot+8+k] = (u8)(back >> (8*k));
    memw(shadow, buf, (u64)slot + 16);

    u8 b4[4];
    for (int k = 0; k < 4; k++) b4[k] = (u8)(bti >> (8*k));
    memw(base + (u64)(n-1)*4, b4, 4);
}

int mc_install(u64 af, u64 hal){
    g_af = af; g_hal = hal;

    /* 原字节只取一次。重复安装时不覆盖，否则会把我们的跳转当成原指令存下来。 */
    if (!g_origok){
        for (int i = 0; i < CAPCOPY; i++) g_corig[i] = ((u8*)(af + CAP_OFF))[i];
        for (int i = 0; i < INJCOPY; i++) g_iorig[i] = ((u8*)(hal + INJ_OFF + 4))[i];
        g_origok = 1;
    }

    /* 采集入口前 5 条指令、注入入口从 +4 起 4 条指令，复制到影子入口 */
    mkshadow(addr_shcap(), af + CAP_OFF, (const u32 *)g_corig, CAPN);
    mkshadow(addr_shinj(), hal + INJ_OFF + 4, (const u32 *)g_iorig, INJN);

    {
        u64 h = addr_of_cap();
        u32 w[3] = {0xd50324dfu, 0x58000050u, 0xd61f0200u};
        for (int i = 0; i < 3; i++) for (int b = 0; b < 4; b++) g_cpatch[i*4+b] = (u8)(w[i] >> (8*b));
        for (int i = 0; i < 8; i++) g_cpatch[12+i] = (u8)(h >> (8*i));
        memw(af + CAP_OFF, g_cpatch, 20);
    }
    {
        u64 h = addr_of_inj();
        u32 w[2] = {0x58000050u, 0xd61f0200u};
        for (int i = 0; i < 2; i++) for (int b = 0; b < 4; b++) g_ipatch[i*4+b] = (u8)(w[i] >> (8*b));
        for (int i = 0; i < 8; i++) g_ipatch[8+i] = (u8)(h >> (8*i));
        memw(hal + INJ_OFF + 4, g_ipatch, 16);
    }
    return 0;
}

int mc_uninstall(void){
    g_on = 0;
    if (g_origok && g_af) memw(g_af + CAP_OFF, g_corig, CAPCOPY);
    if (g_origok && g_hal) memw(g_hal + INJ_OFF + 4, g_iorig, INJCOPY);
    return 0;
}
