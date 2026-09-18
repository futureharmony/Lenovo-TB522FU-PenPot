/*
 * sunia_stub.c -- DIAG v7: v6 logic (eglewInit first, terminate+retry) with a
 * final diagnostic SIGSEGV carrying state in x4..x15.
 */

typedef unsigned long u64;
typedef long long     i64;
typedef unsigned int  u32;
typedef int           i32;

#define OFF_CALLSITE_NEXT      0x697CB0UL

#define OFF_PLT_eglGetDisplay      0xB90C20UL
#define OFF_PLT_eglChooseConfig    0xB90C40UL
#define OFF_PLT_eglCreateContext   0xB90C50UL
#define OFF_PLT_eglMakeCurrent     0xB90C60UL
#define OFF_PLT_eglGetProcAddress  0xB97590UL
#define OFF_PLT_android_log_print  0xB8FD70UL

#define OFF_FN__glewInit           0x83962CUL
#define OFF_FN_eglewInit           0x81D73CUL

#define OFF_VAR_s_display          0xE0ADB0UL
#define OFF_VAR_s_config           0xE0ADB8UL

#define OFF_CFG_ATTRS_ENGINE       0xC48670UL

#define OFF_CELL_eglewInitialize   0xE12F98UL
#define OFF_CELL_eglewMakeCurrent  0xE12FA0UL

#define A(off)   ((off) + base)

int sunia_stub(void)
{
    u64 base = (u64)__builtin_return_address(0) - OFF_CALLSITE_NEXT;

    u64 s_dpy = *(volatile u64*)(base + OFF_VAR_s_display);
    u64 cfg   = *(volatile u64*)(base + OFF_VAR_s_config);
    u64 dpy   = s_dpy;
    u64 ctx   = 0;
    i32 el1 = -1, el2 = -2, mk_ret = -1, gl_ret = -1;

    static const i32 attrs_cfg[]  = { 0x3042, 0x40, 0x3038 };
    static const i32 attrs_none[] = { 0x3038 };

    if (!dpy)
        dpy = (u64)((void* (*)(u64))A(OFF_PLT_eglGetDisplay))(0);

    /* 1st attempt: eglewInit does its own eglInitialize */
    el1 = ((i32 (*)(u64))A(OFF_FN_eglewInit))(dpy);

    /* retry path: terminate (via gpa) then eglewInit again */
    u64 term = ((u64 (*)(const char*))A(OFF_PLT_eglGetProcAddress))("eglTerminate");
    u64 init = ((u64 (*)(const char*))A(OFF_PLT_eglGetProcAddress))("eglInitialize");
    u64 init_ret = 0;
    if (el1 != 0 && term) {
        ((u32 (*)(u64))term)(dpy);
        el2 = ((i32 (*)(u64))A(OFF_FN_eglewInit))(dpy);
    }
    if (el1 != 0 && el2 != 0 && init) {
        i32 maj = 0, min = 0;
        init_ret = (u64)((i32 (*)(u64, i32*, i32*))init)(dpy, &maj, &min);
    }

    /* temp context for the GL table */
    if (!cfg) {
        i32 n = 0;
        ((i32 (*)(u64, const i32*, u64*, i32, i32*))A(OFF_PLT_eglChooseConfig))
                    (dpy, attrs_cfg, &cfg, 1, &n);
        if (!cfg)
            ((i32 (*)(u64, const i32*, u64*, i32, i32*))A(OFF_PLT_eglChooseConfig))
                    (dpy, attrs_none, &cfg, 1, &n);
    }
    if (cfg) {
        ctx = (u64)((void* (*)(u64, u64, void*, const i32*))A(OFF_PLT_eglCreateContext))
                    (dpy, cfg, 0, (const i32*)A(OFF_CFG_ATTRS_ENGINE));
        if (ctx) {
            mk_ret = ((i32 (*)(u64, void*, void*, void*))A(OFF_PLT_eglMakeCurrent))
                    (dpy, 0, 0, (void*)ctx);
            if (mk_ret)
                gl_ret = ((i32 (*)(void))A(OFF_FN__glewInit))();
        }
    }

    /* ---- diagnostic crash ---- */
    register u64 r4 = s_dpy;
    register u64 r5 = dpy;
    register u64 r6 = (u64)(i64)el1;
    register u64 r7 = term;
    register u64 r8 = (u64)(i64)el2;
    register u64 r9 = init_ret;
    register u64 r10 = (u64)(i64)gl_ret;
    register u64 r11 = *(volatile u64*)A(OFF_CELL_eglewInitialize);
    register u64 r12 = *(volatile u64*)A(OFF_CELL_eglewMakeCurrent);
    register u64 r13 = cfg;
    register u64 r14 = ctx;

    __asm__ __volatile__(
        "mov x4, %0\n\t" "mov x5, %1\n\t" "mov x6, %2\n\t" "mov x7, %3\n\t"
        "mov x8, %4\n\t" "mov x9, %5\n\t" "mov x10, %6\n\t" "mov x11, %7\n\t"
        "mov x12, %8\n\t" "mov x13, %9\n\t" "mov x14, %10\n\t"
        "str x4, [%11]\n\t"
        :
        : "r"(r4), "r"(r5), "r"(r6), "r"(r7), "r"(r8), "r"(r9), "r"(r10),
          "r"(r11), "r"(r12), "r"(r13), "r"(r14), "r"((u64)0x0DEAD0000DEADUL)
        : "x4","x5","x6","x7","x8","x9","x10","x11","x12","x13","x14",
          "memory","cc");

    return 0;
}
