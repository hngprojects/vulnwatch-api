using Application.Features.Auth;
using Application.Features.Auth.DTOs;
using Application.Features.Support;
using Application.Features.Support.DTOs;
using Domain.Common;
using MediatR;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Web.Extensions;

namespace Web.Controllers;

[EnableRateLimiting(RateLimitExtensions.GeneralPolicy)]
[ApiController]
[Route("api/[controller]")]
public class SupportController : ControllerBase
{
    private readonly IMediator _mediator;

    public SupportController(IMediator mediator) => _mediator = mediator;

    /// <summary>
    /// Submits a support or contact request.
    /// </summary>
    /// <param name="request">
    /// Contact form payload including user details and message content.
    /// </param>
    /// <response code="200">Support request successfully submitted.</response>
    /// <response code="400">Invalid request payload.</response>
    [HttpPost]
    public async Task<ActionResult<Result<ContactUsResponse>>> ContactUs(ContactUsRequest request)
    {
        var result = await _mediator.Send(
            new ContactUsCommand(
                request.Name,
                request.Email,
                request.PhoneNumber,
                request.RequestType,
                request.Content));

        return result.ToHttpResponse(this);
    }
}