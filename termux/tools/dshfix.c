/*
 * dshfix —— 把目录树中所有文件里的 "com.termux" 就地改写为 "com.dshapp"。
 *
 * 背景：Termux 官方仓库的软件包把 /data/data/com.termux/files/usr 硬编码进了
 * 脚本 shebang、ELF 的 RUNPATH 与默认路径。合并版包名是 com.dshapp，直接安装
 * 这些包会得到无法运行的二进制（动态链接器去 com.termux 找库）。
 *
 * 两个包名长度相同（都是 10 字节），因此可以等长字节替换，不会破坏 ELF 内部偏移。
 * 本程序按字节处理，对文本与二进制一视同仁。
 *
 * 用法：dshfix <目录>...
 */
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static const char OLD[] = "com.termux";
static const char NEW[] = "com.dshapp";
#define PATTERN_LEN 10

static int fix_file(const char *path) {
    struct stat st;
    if (lstat(path, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0) {
        return 0;
    }
    size_t size = (size_t) st.st_size;
    FILE *fp = fopen(path, "r+b");
    if (fp == NULL) {
        return 0;
    }
    unsigned char *buf = (unsigned char *) malloc(size);
    if (buf == NULL) {
        fclose(fp);
        return 0;
    }
    if (fread(buf, 1, size, fp) != size) {
        free(buf);
        fclose(fp);
        return 0;
    }

    int changed = 0;
    for (size_t i = 0; i + PATTERN_LEN <= size; i++) {
        if (memcmp(buf + i, OLD, PATTERN_LEN) == 0) {
            memcpy(buf + i, NEW, PATTERN_LEN);
            changed = 1;
            i += PATTERN_LEN - 1;
        }
    }

    if (changed) {
        if (fseek(fp, 0, SEEK_SET) == 0) {
            fwrite(buf, 1, size, fp);
            fflush(fp);
        } else {
            changed = 0;
        }
    }
    free(buf);
    fclose(fp);
    return changed;
}

static int walk(const char *dir) {
    DIR *dp = opendir(dir);
    if (dp == NULL) {
        return 0;
    }
    int count = 0;
    struct dirent *entry;
    while ((entry = readdir(dp)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
            continue;
        }
        char path[4096];
        int n = snprintf(path, sizeof(path), "%s/%s", dir, entry->d_name);
        if (n < 0 || (size_t) n >= sizeof(path)) {
            continue;
        }
        struct stat st;
        if (lstat(path, &st) != 0) {
            continue;
        }
        if (S_ISDIR(st.st_mode)) {
            count += walk(path);
        } else if (S_ISREG(st.st_mode)) {
            count += fix_file(path);
        }
    }
    closedir(dp);
    return count;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: dshfix <dir>...\n");
        return 2;
    }
    int total = 0;
    for (int i = 1; i < argc; i++) {
        total += walk(argv[i]);
    }
    printf("dshfix: %d file(s) rewritten\n", total);
    return 0;
}
