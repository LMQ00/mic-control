/* payload61: 只做注入。libaudiohal@aidl.so+0x168b30 StreamInHalAidl::read
 * 入口 bti c 保留，跳板从 entry+4 起 12 字节。处理器调用真实函数后覆盖缓冲区。
 */
typedef unsigned long u64; typedef long i64; typedef unsigned int u32;
typedef unsigned char u8; typedef signed short s16; typedef signed int i32;

#define CAP_OFF 0x1a4c14UL
#define INJ_OFF 0x168b30UL
#define CAPLEN 20
#define INJLEN 16
#define RING_FRAMES (1024u*512u)

static inline i64 sy(i64 n,i64 a,i64 b,i64 c,i64 d,i64 e,i64 f){
    register i64 x8 __asm__("x8")=n; register i64 x0 __asm__("x0")=a;
    register i64 x1 __asm__("x1")=b; register i64 x2 __asm__("x2")=c;
    register i64 x3 __asm__("x3")=d; register i64 x4 __asm__("x4")=e;
    register i64 x5 __asm__("x5")=f;
    __asm__ volatile("svc #0":"+r"(x0):"r"(x8),"r"(x1),"r"(x2),"r"(x3),"r"(x4),"r"(x5):"memory");
    return x0;
}
#define SYS_openat 56
#define SYS_close 57
#define SYS_write 64
#define SYS_pwrite64 68
#define AT_FDCWD (-100)

__attribute__((visibility("hidden"))) static u64 g_hal, g_af;
__attribute__((visibility("hidden"))) static u8 g_corig[CAPLEN], g_cpatch[CAPLEN];
__attribute__((visibility("hidden"))) static volatile u32 g_lockc;
__attribute__((visibility("hidden"))) static volatile u64 g_target;
__attribute__((visibility("hidden"))) static u64 g_capc, g_capt;
__attribute__((visibility("hidden"))) static u64 g_uid[8], g_uidc[8], g_uidn;
__attribute__((visibility("hidden"))) static u8 g_orig[INJLEN], g_patch[INJLEN];
__attribute__((visibility("hidden"))) static i64 g_mem=-1;
__attribute__((visibility("hidden"))) static volatile u32 g_lock;
__attribute__((visibility("hidden"))) static s16 g_ring[RING_FRAMES*2];
__attribute__((visibility("hidden"))) static u64 g_wf, g_rf;
__attribute__((visibility("hidden"))) static volatile u32 g_on;
__attribute__((visibility("hidden"))) static u64 g_injc, g_subs, g_skip, g_ch;

static void lkc(void){ u32 v=1,st=1;
    while(st){
        __asm__ volatile("ldaxr %w0, [%1]":"=r"(v):"r"(&g_lockc):"memory");
        if(v) continue;
        __asm__ volatile("stxr %w0, %w2, [%1]":"=&r"(st):"r"(&g_lockc),"r"(v):"memory");
    } }
static void ulc(void){ __asm__ volatile("stlr wzr, [%0]"::"r"(&g_lockc):"memory"); }
static void lk(void){ u32 v=1,st=1;
    while(st){
        __asm__ volatile("ldaxr %w0, [%1]":"=r"(v):"r"(&g_lock):"memory");
        if(v) continue;
        __asm__ volatile("stxr %w0, %w2, [%1]":"=&r"(st):"r"(&g_lock),"r"(v):"memory");
    } }
static void ul(void){ __asm__ volatile("stlr wzr, [%0]"::"r"(&g_lock):"memory"); }
static void flushc(u64 a,u64 n){
    u64 c; __asm__ volatile("mrs %0, ctr_el0":"=r"(c));
    u64 dl=4UL<<((c>>16)&0xf), il=4UL<<(c&0xf);
    for(u64 x=a&~(dl-1);x<a+n;x+=dl) __asm__ volatile("dc cvau, %0"::"r"(x):"memory");
    __asm__ volatile("dsb ish":::"memory");
    for(u64 x=a&~(il-1);x<a+n;x+=il) __asm__ volatile("ic ivau, %0"::"r"(x):"memory");
    __asm__ volatile("dsb ish; isb":::"memory");
}
static void memw(u64 a,const void*d,u64 n){
    if(g_mem<0) g_mem=sy(SYS_openat,AT_FDCWD,(i64)"/proc/self/mem",2,0,0,0);
    if(g_mem>=0) sy(SYS_pwrite64,g_mem,(i64)d,(i64)n,(i64)a,0,0);
    flushc(a,n);
}
static u64 avail(void){
    __asm__ volatile("dmb ish":::"memory");
    u64 w=g_wf,r=g_rf;
    if(w-r>RING_FRAMES){ g_rf=w-RING_FRAMES; r=g_rf; }
    return w-r;
}

extern i64 mc_cap_c(void*,void*);
__attribute__((used,noinline))
i64 mc_cap_c(void* self,void* buf){
    i64 r;
    lkc(); memw(g_af+CAP_OFF,g_corig,CAPLEN);
    r=((i64(*)(void*,void*))(g_af+CAP_OFF))(self,buf);
    memw(g_af+CAP_OFF,g_cpatch,CAPLEN); ulc();
    g_capc++;
    if(!self||!buf||r!=0||!g_on) return r;
    u64 uid=*(u32*)((u8*)self+0x1b4);
    u64 k;
    for(k=0;k<g_uidn;k++) if(g_uid[k]==uid){ g_uidc[k]++; break; }
    if(k==g_uidn&&g_uidn<8){ g_uid[g_uidn]=uid; g_uidc[g_uidn]=1; g_uidn++; }
    u64 t=g_target;
    if(t&&uid!=t) return r;
    float* f=*(float**)buf;
    u64 fc=*(u64*)((u8*)buf+8);
    if(!f||!fc) return r;
    u64 w=g_wf;
    for(u64 i=0;i<fc;i++){
        u64 p=(w+i)%RING_FRAMES;
        float L=f[i*2], R=f[i*2+1];
        if(L>1.0f)L=1.0f; if(L<-1.0f)L=-1.0f;
        if(R>1.0f)R=1.0f; if(R<-1.0f)R=-1.0f;
        g_ring[p*2]  =(s16)(L*32767.0f);
        g_ring[p*2+1]=(s16)(R*32767.0f);
    }
    __asm__ volatile("dmb ish":::"memory");
    g_wf=w+fc;
    g_capt++;
    return r;
}
__asm__(
".text\n.global mc_cap\n.type mc_cap, %function\n.p2align 2\n"
"mc_cap:\n  bti jc\n  b mc_cap_c\n.size mc_cap, .-mc_cap\n"
);
extern i64 mc_cap(void*,void*);
u64 mc_h_cap(void){ return (u64)(void*)mc_cap; }
u64 mc_capc(void){ return g_capc; }
u64 mc_capt(void){ return g_capt; }
u64 mc_uidn(void){ return g_uidn; }
u64 mc_uid(u64 i){ return i<g_uidn?g_uid[i]:0; }
u64 mc_uidc(u64 i){ return i<g_uidn?g_uidc[i]:0; }
void mc_set_target(u64 u){ g_target=u; }
__attribute__((used,noinline))
i64 mc_inj_c(void* self,void* buf,u64 bytes,u64* outN){
    i64 r;
    lk(); memw(g_hal+INJ_OFF+4,g_orig,INJLEN);
    r=((i64(*)(void*,void*,u64,u64*))(g_hal+INJ_OFF))(self,buf,bytes,outN);
    memw(g_hal+INJ_OFF+4,g_patch,INJLEN); ul();
    g_injc++;
    if(r!=0||!self||!buf||!bytes||!g_on) return r;
    u64 ch=*(u32*)((u8*)self+0x4c);
    if(ch<1||ch>8) ch=2;
    g_ch=ch;
    u64 fsz=2*ch;
    u64 frames=bytes/fsz;
    if(!frames) return r;
    if(avail()<frames){ g_skip++; return r; }
    u64 rp=g_rf;
    s16* o=(s16*)buf;
    for(u64 i=0;i<frames;i++){
        u64 p=(rp+i)%RING_FRAMES;
        s16 L=g_ring[p*2], R=g_ring[p*2+1];
        if(ch==1) o[i]=(s16)(((i32)L+(i32)R)/2);
        else{
            o[i*ch]=L; o[i*ch+1]=R;
            for(u64 c=2;c<ch;c++) o[i*ch+c]=L;
        }
    }
    __asm__ volatile("dmb ish":::"memory");
    g_rf=rp+frames;
    if(outN) *outN=frames*fsz;
    g_subs++;
    return r;
}
u64 mc_h_inj(void){ return (u64)(void*)mc_inj_c; }
u64 mc_injc(void){ return g_injc; }
u64 mc_subs(void){ return g_subs; }
u64 mc_skip(void){ return g_skip; }
u64 mc_ch(void){ return g_ch; }
u64 mc_avail(void){ return avail(); }
void mc_on(void){ g_on=1; }
void mc_off(void){ g_on=0; }
void mc_reset(void){ g_wf=g_rf=g_injc=g_subs=g_skip=0; }
int mc_fillpat(u64 v,u64 frames){
    if(frames>RING_FRAMES) frames=RING_FRAMES;
    for(u64 i=0;i<frames;i++){ g_ring[i*2]=(s16)v; g_ring[i*2+1]=(s16)v; }
    __asm__ volatile("dmb ish":::"memory");
    g_wf=frames; g_rf=0;
    return 0;
}
int mc_install(u64 af,u64 hal,u64 ch,u64 ih){
    g_af=af; g_hal=hal;
    for(int i=0;i<CAPLEN;i++) g_corig[i]=((u8*)(af+CAP_OFF))[i];
    {
        u32 w[3]={0xd50324dfu,0x58000050u,0xd61f0200u};
        for(int i=0;i<3;i++) for(int b=0;b<4;b++) g_cpatch[i*4+b]=(u8)(w[i]>>(8*b));
        for(int i=0;i<8;i++) g_cpatch[12+i]=(u8)(ch>>(8*i));
        memw(g_af+CAP_OFF,g_cpatch,CAPLEN);
    }
    for(int i=0;i<INJLEN;i++) g_orig[i]=((u8*)(hal+INJ_OFF+4))[i];
    u32 w[2]={0x58000050u,0xd61f0200u};
    for(int i=0;i<2;i++) for(int b=0;b<4;b++) g_patch[i*4+b]=(u8)(w[i]>>(8*b));
    for(int i=0;i<8;i++) g_patch[8+i]=(u8)(ih>>(8*i));
    memw(g_hal+INJ_OFF+4,g_patch,INJLEN);
    return 0;
}
int mc_uninstall(void){
    if(g_af) memw(g_af+CAP_OFF,g_corig,CAPLEN);
    if(g_hal) memw(g_hal+INJ_OFF+4,g_orig,INJLEN);
    return 0;
}
