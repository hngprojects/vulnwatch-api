// using Application.Interfaces;
// using Domain.Enums;
// using Microsoft.AspNetCore.Mvc;
// using Microsoft.EntityFrameworkCore;
// using Infrastructure.Persistence;

// namespace Web.Controllers;

// /// <summary>
// /// Temporary debug endpoints — only available when Debug:Enabled = true.
// /// Remove before production release.
// /// </summary>
// [ApiController]
// [Route("api/debug")]
// public class DebugController(
//     VulnWatchDbContext db,
//     IConfiguration config) : ControllerBase
// {
//     private bool IsDebugEnabled =>
//         config.GetValue<bool>("Debug:Enabled");

//     // GET /api/debug/monitoring
//     // Shows all domains with their monitoring settings and SSL state
//     [HttpGet("monitoring")]
//     public async Task<IActionResult> GetMonitoringState(
//         CancellationToken ct)
//     {
//         if (!IsDebugEnabled)
//             return NotFound();

//         var today = DateTime.UtcNow.Date;

//         var rows = await db.DomainSettings
//             .Include(s => s.Domain)
//             .OrderBy(s => s.Domain.DomainName)
//             .Select(s => new
//             {
//                 DomainId = s.DomainId,
//                 DomainName = s.Domain.DomainName,
//                 Verified = s.Domain.VerificationStatus
//                                       == VerificationStatus.Verified,
//                 MonitoringEnabled = s.MonitoringEnabled,
//                 ScanFrequency = s.ScanFrequency.ToString(),
//                 SslAlertThresholds = s.SslAlertThresholds,

//                 // SSL state
//                 SslCertExpiry = s.Domain.SslCertExpiry,
//                 DaysRemaining = s.Domain.SslCertExpiry == null
//                     ? (int?)null
//                     : (int)(s.Domain.SslCertExpiry.Value.UtcDateTime.Date
//                             - today).TotalDays,

//                 // Scheduling state
//                 LastMonitoredAt = s.LastMonitoredAt,
//                 NextScheduledAt = s.NextScheduledAt,
//                 IsDueNow = s.MonitoringEnabled
//                     && s.Domain.VerificationStatus == VerificationStatus.Verified
//                     && (s.NextScheduledAt == null
//                         || s.NextScheduledAt <= DateTime.UtcNow),
//             })
//             .ToListAsync(ct);

//         return Ok(new
//         {
//             AsOf = DateTime.UtcNow,
//             TotalDomains = rows.Count,
//             MonitoringOn = rows.Count(r => r.MonitoringEnabled),
//             DueNow = rows.Count(r => r.IsDueNow),
//             WithSslExpiry = rows.Count(r => r.DaysRemaining != null),
//             Domains = rows
//         });
//     }

//     // PATCH /api/debug/monitoring/{domainId}/ssl-expiry
//     // Backdates the SSL expiry so DaysRemaining hits a threshold
//     // e.g. body: { "daysFromNow": 7 }
//     [HttpPatch("monitoring/{domainId:guid}/ssl-expiry")]
//     public async Task<IActionResult> SetSslExpiry(
//         Guid domainId,
//         [FromBody] SetSslExpiryRequest request,
//         CancellationToken ct)
//     {
//         if (!IsDebugEnabled)
//             return NotFound();

//         var domain = await db.Domains
//             .FirstOrDefaultAsync(d => d.Id == domainId, ct);

//         if (domain is null)
//             return NotFound(new { error = "Domain not found" });

//         var newExpiry = new DateTimeOffset(
//             DateTime.UtcNow.Date.AddDays(request.DaysFromNow),
//             TimeSpan.Zero);

//         domain.SetSslCertExpiry(newExpiry);
//         await db.SaveChangesAsync(ct);

//         return Ok(new
//         {
//             DomainId = domainId,
//             DomainName = domain.DomainName,
//             SslCertExpiry = domain.SslCertExpiry,
//             DaysRemaining = request.DaysFromNow,
//             Message = $"SSL expiry set to {request.DaysFromNow} days from now"
//         });
//     }

//     // PATCH /api/debug/monitoring/{domainId}/thresholds
//     // Updates SSL alert thresholds for a domain
//     // e.g. body: { "thresholds": [30, 14, 7, 3] }
//     [HttpPatch("monitoring/{domainId:guid}/thresholds")]
//     public async Task<IActionResult> SetThresholds(
//         Guid domainId,
//         [FromBody] SetThresholdsRequest request,
//         CancellationToken ct)
//     {
//         if (!IsDebugEnabled)
//             return NotFound();

//         var settings = await db.DomainSettings
//             .FirstOrDefaultAsync(s => s.DomainId == domainId, ct);

//         if (settings is null)
//             return NotFound(new { error = "Monitoring settings not found for domain" });

//         try
//         {
//             settings.UpdateSettings(
//                 settings.MonitoringEnabled,
//                 settings.ScanFrequency,
//                 request.Thresholds,
//                 settings.NotificationChannel);
//         }
//         catch (ArgumentException ex)
//         {
//             return BadRequest(new { error = ex.Message });
//         }

//         await db.SaveChangesAsync(ct);

//         return Ok(new
//         {
//             DomainId = domainId,
//             SslAlertThresholds = settings.GetSslAlertThresholds(),
//             Message = "Thresholds updated"
//         });
//     }

//     // POST /api/debug/monitoring/{domainId}/force-tick
//     // Forces the worker logic to run for one domain right now
//     // without waiting for NextScheduledAt
//     [HttpPost("monitoring/{domainId:guid}/force-tick")]
//     public async Task<IActionResult> ForceTick(
//         Guid domainId,
//         [FromServices] Web.Workers.Monitoring.SslExpiryCheckService sslCheck,
//         CancellationToken ct)
//     {
//         if (!IsDebugEnabled)
//             return NotFound();

//         var settings = await db.DomainSettings
//             .Include(s => s.Domain)
//             .FirstOrDefaultAsync(s => s.DomainId == domainId, ct);

//         if (settings is null)
//             return NotFound(new { error = "Monitoring settings not found" });

//         var today = DateTime.UtcNow.Date;
//         var daysRemaining = settings.Domain.SslCertExpiry is null
//             ? (int?)null
//             : (int)(settings.Domain.SslCertExpiry.Value.UtcDateTime.Date
//                     - today).TotalDays;

//         var thresholds = settings.GetSslAlertThresholds();
//         var willFire = daysRemaining.HasValue
//                       && thresholds.Contains(daysRemaining.Value);

//         // Run just the SSL check
//         await sslCheck.CheckAsync(settings, ct);

//         return Ok(new
//         {
//             DomainId = domainId,
//             DomainName = settings.Domain.DomainName,
//             DaysRemaining = daysRemaining,
//             Thresholds = thresholds,
//             AlertShouldFire = willFire,
//             Message = willFire
//                 ? "SSL alert dispatched — check Alerts table"
//                 : $"No alert fired — {daysRemaining} days remaining is not in thresholds [{string.Join(", ", thresholds)}]"
//         });
//     }
// }

// public record SetSslExpiryRequest(int DaysFromNow);
// public record SetThresholdsRequest(List<int> Thresholds);