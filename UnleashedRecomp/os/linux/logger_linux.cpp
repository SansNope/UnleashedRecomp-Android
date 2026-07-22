#include <os/logger.h>

void os::logger::Init()
{
}

void os::logger::Heartbeat()
{
}

void os::logger::SetWatchdogSuspended(bool suspended)
{
}

void os::logger::SetTestLocation(const std::string_view location)
{
}

void os::logger::Log(const std::string_view str, ELogType type, const char* func)
{
    if (func)
    {
        fmt::println("[{}] {}", func, str);
    }
    else
    {
        fmt::println("{}", str);
    }
}
