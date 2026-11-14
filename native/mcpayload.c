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
typedef unsigned char u8; typedef signed short s16; typedef signed int i32;

#define CAP_OFF 0x1a4c14UL
#define INJ_OFF 0x168b30UL
#define CAPCOPY 20              /* 采集入口覆盖的字节数 */
#define INJCOPY 16              /* 注入入口从 entry+4 起覆盖的字节数 */
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
__attribute__((visibility("hidden"))) static volatile u32 g_locks, g_lockc;
static void lkc(void){ u32 v=1,st=1;
    while(st){
        __asm__ volatile("ldaxr %w0, [%1]":"=r"(v):"r"(&g_lockc):"memory");
        if(v) continue;
        __asm__ volatile("stxr %w0, %w2, [%1]":"=&r"(st):"r"(&g_lockc),"r"(v):"memory");
    } }
static void ulc(void){ __asm__ volatile("stlr wzr, [%0]"::"r"(&g_lockc):"memory"); }
static void lk(void){ u32 v=1,st=1;
    while(st){
        __asm__ volatile("ldaxr %w0, [%1]":"=r"(v):"r"(&g_locks):"memory");
        if(v) continue;
        __asm__ volatile("stxr %w0, %w2, [%1]":"=&r"(st):"r"(&g_locks),"r"(v):"memory");
    } }
static void ul(void){ __asm__ volatile("stlr wzr, [%0]"::"r"(&g_locks):"memory"); }
/* 采集源：只从一条音轨抄 */
__attribute__((visibility("hidden"))) static volatile u64 g_src;
__attribute__((visibility("hidden"))) static u64 g_srctick;
__attribute__((visibility("hidden"))) static u64 g_capraw, g_capfc;
__attribute__((visibility("hidden"))) static u64 g_lastbytes, g_lastframes, g_lastfsz;

/* 用相邻两次调用的单调时钟间隔反推流的实际采样率。
   采样率字段在所有流上读到的是同一个值，靠不住。 */
static inline u64 now_ns(void){
    u64 t;
    __asm__ volatile("isb; mrs %0, cntvct_el0" : "=r"(t));
    return t;
}
static inline u64 ns_per_tick(void){
    u64 f;
    __asm__ volatile("mrs %0, cntfrq_el0" : "=r"(f));
    return f ? (1000000000ULL / f) : 0;
}

typedef struct { u64 self; u64 rp; u64 frac; u64 rate; u64 ch; u64 lastt; u64 cnt; i32 last; } st_t;
__attribute__((visibility("hidden"))) static st_t g_st[MAX_STREAMS];
__attribute__((visibility("hidden"))) static u32 g_stn;

/* 新流从“最旧可用数据”开始读。缓冲里最多留 200 毫秒的积压，
   新流取这段的起点，既不会读空，也不会落后太多。 */
static st_t* st_find(u64 self){
    for (u32 i = 0; i < g_stn; i++) if (g_st[i].self == self) return &g_st[i];
    if (g_stn >= MAX_STREAMS) return 0;
    st_t *p = &g_st[g_stn++];
    u64 wf = g_wf;
    p->self = self;
    p->rp = wf > MAX_BACKLOG ? wf - MAX_BACKLOG : 0;
    p->frac = 0;
    p->rate = 48000;
    p->ch = 1;
    p->lastt = 0;
    p->cnt = 0;
    p->last = 0;
    return p;
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
static inline s16 f2s(float x){
    if (x >  1.0f) x =  1.0f;
    if (x < -1.0f) x = -1.0f;
    return (s16)(x * 32767.0f);
}

/* 采集：目标应用的播放复制进环形缓冲。 */
__attribute__((used, noinline))
i64 mc_cap_c(void *self, void *buf){
    i64 r;
    lkc(); memw(g_af + CAP_OFF, g_corig, CAPCOPY);
    r = ((i64(*)(void*,void*))(g_af + CAP_OFF))(self, buf);
    memw(g_af + CAP_OFF, g_cpatch, CAPCOPY); ulc();
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

    /* 这个缓冲里是 16 位整数立体声，原样搬即可 */
    s16 *pcm = *(s16 **)buf;
    u64 fc = *(u64 *)((u8 *)buf + 8);
    g_capraw = (u64)pcm;
    g_capfc = fc;
    if (!pcm || !fc) return r;

    u64 w = g_wf;
    for (u64 i = 0; i < fc; i++){
        u64 p = (w + i) % RING_FRAMES;
        g_ring[p*2]   = pcm[i*2];
        g_ring[p*2+1] = pcm[i*2+1];
    }
    __asm__ volatile("dmb ish":::"memory");
    g_wf = w + fc;
    g_srctick = g_wf;
    g_capt++;
    return r;
}

/* 注入：用环形缓冲覆盖 HAL 交付的录音数据。
   原函数开头含 PC 相对指令，不能搬到影子入口里重放，
   改回「调用前恢复原字节、返回后重写跳板」。 */
__attribute__((used, noinline))
i64 mc_inj_c(void *self, void *buf, u64 bytes, u64 *outN){
    i64 r;
    lk(); memw(g_hal + INJ_OFF + 4, g_iorig, INJCOPY);
    r = ((i64(*)(void*,void*,u64,u64*))(g_hal + INJ_OFF))(self, buf, bytes, outN);
    memw(g_hal + INJ_OFF + 4, g_ipatch, INJCOPY); ul();
    g_injc++;
    if (r != 0 || !self || !buf || !bytes || !g_on) return r;

    st_t *sp = st_find((u64)self);
    if (!sp) return r;

    /* 采样率和声道数按流各自记住。系统里同时有 16000 和 48000 的流，
       共用一个全局值会让后调用的流把前者的参数覆盖掉。 */
    u64 ch = *(u32 *)((u8 *)self + 0x4c);
    if (ch < 1 || ch > 8) ch = sp->ch;
    sp->ch = ch;
    g_ch = ch;

    u64 fsz = 2 * ch;
    u64 frames = bytes / fsz;
    if (!frames) return r;

    /* 采样率靠相邻两次调用的间隔算出来：每次交付的帧数固定，
       间隔越长说明采样率越低。 */
    u64 t = now_ns(), tick = ns_per_tick();
    if (sp->lastt && tick){
        u64 dt = (t - sp->lastt) * tick;
        if (dt > 1000000ULL && dt < 500000000ULL){
            u64 est = (frames * 1000000000ULL) / dt;
            if (est >= 8000 && est <= 192000){
                /* 吸附到标准采样率。推断值总有百分之几的误差，
                   直接拿去换算会让每帧步长偏离 1.0，声音被拖慢。 */
                static const u64 std[] = {8000,11025,12000,16000,22050,24000,
                                          32000,44100,48000,64000,88200,96000,192000};
                u64 best = 48000, bd = ~0ULL;
                for (u32 k = 0; k < sizeof(std)/sizeof(std[0]); k++){
                    u64 d2 = est > std[k] ? est - std[k] : std[k] - est;
                    if (d2 < bd){ bd = d2; best = std[k]; }
                }
                /* 只在估算值离标准值足够近时才采纳，避免被抖动带偏 */
                if (bd * 100 <= best * 15) sp->rate = best;
            }
        }
    }
    sp->lastt = t;
    sp->cnt++;
    u64 rate = sp->rate;
    g_rate = rate;

    s16 *o = (s16 *)buf;
    u64 wf = g_wf;
    g_lastbytes = bytes; g_lastframes = frames; g_lastfsz = fsz;

    /* 还没抄到音乐：输出静音 */
    if (wf < 2){
        for (u64 i = 0; i < bytes / 2; i++) o[i] = 0;
        if (outN) *outN = bytes;
        g_subs++;
        return r;
    }

    if (wf > sp->rp && wf - sp->rp > MAX_BACKLOG) sp->rp = wf - MAX_BACKLOG;

    u64 rp = sp->rp;
    u64 step = (48000ULL << 16) / rate;
    u64 pos = sp->frac;
    i32 last = sp->last;

    for (u64 i = 0; i < frames; i++){
        u64 src = pos >> 16;
        if (rp + src < wf){
            u64 p = (rp + src) % RING_FRAMES;
            i32 L = g_ring[p*2], R = g_ring[p*2+1];
            last = (ch == 1) ? (L + R) / 2 : L;
            if (ch == 1) o[i] = (s16)last;
            else {
                o[i*ch] = (s16)L;
                o[i*ch+1] = (s16)R;
                for (u64 c = 2; c < ch; c++) o[i*ch+c] = (s16)L;
            }
        } else {
            if (ch == 1) o[i] = (s16)last;
            else for (u64 c = 0; c < ch; c++) o[i*ch+c] = (s16)last;
        }
        pos += step;
    }
    sp->frac = pos & 0xFFFF;
    sp->last = last;
    sp->rp = rp + (pos >> 16);
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
void mc_on(void){ g_stn = 0; g_src = 0; g_srctick = 0; g_on = 1; }
void mc_off(void){ g_on = 0; }
void mc_reset(void){
    __asm__ volatile("dmb ish":::"memory");
    g_stn = 0; g_src = 0; g_srctick = 0;
    g_capc = g_capt = g_injc = g_subs = 0;
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
    u64 step = (freq << 22) / 48000u;    /* 每帧推进的表内相位 */
    u64 ph = 0;
    for (u64 i = 0; i < frames; i++){
        s16 v = tbl[(ph >> 16) % 64u];
        ph += step;
        g_ring[i*2] = v; g_ring[i*2+1] = v;
    }
    __asm__ volatile("dmb ish":::"memory");
    g_wf = frames; g_stn = 0;
    return 0;
}

int mc_fillpat(u64 v, u64 frames){
    if (frames > RING_FRAMES) frames = RING_FRAMES;
    for (u64 i = 0; i < frames; i++){ g_ring[i*2] = (s16)v; g_ring[i*2+1] = (s16)v; }
    __asm__ volatile("dmb ish":::"memory");
    g_wf = frames; g_stn = 0;
    return 0;
}

/* 入口跳转：bti jc + ldr x16,#8 + br x16 + 处理器地址 */
static void mkentry(u64 entry, u64 handler){
    u8 b[16];
    u32 jc = 0xd50324dfU, ldr = 0x58000050u, br = 0xd61f0200u;
    for (int k = 0; k < 4; k++) b[k] = (u8)(jc >> (8*k));
    for (int k = 0; k < 4; k++) b[4+k] = (u8)(ldr >> (8*k));
    for (int k = 0; k < 4; k++) b[8+k] = (u8)(br >> (8*k));
    for (int k = 0; k < 8; k++) b[12+k] = (u8)(handler >> (8*k));
    memw(entry, b, 16);
}

int mc_install(u64 af, u64 hal){
    g_af = af; g_hal = hal;

    /* 原字节只取一次。重复安装时不覆盖，否则会把我们的跳转当成原指令存下来。 */
    if (!g_origok){
        for (int i = 0; i < CAPCOPY; i++) g_corig[i] = ((u8*)(af + CAP_OFF))[i];
        for (int i = 0; i < INJCOPY; i++) g_iorig[i] = ((u8*)(hal + INJ_OFF + 4))[i];
        g_origok = 1;
    }

    {
        u64 h = addr_of_cap();
        u32 w[3] = {0xd50324dfu, 0x58000050u, 0xd61f0200u};
        for (int i = 0; i < 3; i++) for (int b = 0; b < 4; b++) g_cpatch[i*4+b] = (u8)(w[i] >> (8*b));
        for (int i = 0; i < 8; i++) g_cpatch[12+i] = (u8)(h >> (8*i));
        memw(af + CAP_OFF, g_cpatch, CAPCOPY);
    }
    {
        u64 h = addr_of_inj();
        u32 w[2] = {0x58000050u, 0xd61f0200u};
        for (int i = 0; i < 2; i++) for (int b = 0; b < 4; b++) g_ipatch[i*4+b] = (u8)(w[i] >> (8*b));
        for (int i = 0; i < 8; i++) g_ipatch[8+i] = (u8)(h >> (8*i));
        memw(hal + INJ_OFF + 4, g_ipatch, INJCOPY);
    }
    return 0;
}

int mc_uninstall(void){
    g_on = 0;
    if (g_origok && g_af) memw(g_af + CAP_OFF, g_corig, CAPCOPY);
    if (g_origok && g_hal) memw(g_hal + INJ_OFF + 4, g_iorig, INJCOPY);
    return 0;
}
