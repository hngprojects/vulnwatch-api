// using Domain.Common;
// using Domain.Enums;
// using MediatR;
// using Microsoft.AspNetCore.Http;

// namespace Application.Features.Scans;

// public record ScanSummary(Guid ScanId, string Status, DateTime CreatedAt);

// public record GetScanHistoryQuery(Guid DomainId, Guid UserId, ScanStatus? Status,  string SortBy = "created_at",
//     string Order = "asc", int Page = 1, int PageSize = 20)
//     : IRequest<Result<PagedResult<ScanSummary>>>;

// public class GetScanHistoryHandler(
//     IHttpContextAccessor _http,
//     IScannedDomainRepository domains,
//     ICurrentUser currentUser)
//     : IRequestHandler<GetDomainsQuery, Result<PagedResult<DomainSummary>>>
// {
    
// }
