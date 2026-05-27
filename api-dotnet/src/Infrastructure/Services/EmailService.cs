using System.Net;
using System.Net.Mail;
using Application.Interfaces;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging;

namespace Infrastructure.Services;

public class EmailService : IEmailService
{
    private readonly IConfiguration _config;
    private readonly ILogger<EmailService> _logger;

    public EmailService(IConfiguration config, ILogger<EmailService> logger)
    {
        _config = config;
        _logger = logger;
    }

    public async Task SendAsync(string to, string subject, string body)
    {
        SmtpCredentials? credentials;

        try
        {
            credentials = SmtpCredentials.Load(_config);
        }
        catch (InvalidOperationException ex)
        {
            _logger.LogError(ex, "Email service is misconfigured. Email to {Recipient} was not sent.", to);
            return;
        }

        using var client = new SmtpClient(credentials.Host, credentials.Port)
        {
            Credentials = new NetworkCredential(credentials.Username, credentials.Password),
            EnableSsl = true
        };

        try
        {
            var mail = new MailMessage
            {
                From = new MailAddress(credentials.Username, credentials.FromName),
                To = { new MailAddress(to) },
                Subject = subject,
                Body = body,
                IsBodyHtml = true
            };


            await client.SendMailAsync(mail);

            // _logger.LogInformation("Email sent to {Recipient} with subject '{Subject}'.", to, subject);
            return;
        }
        catch (SmtpException ex)
        {
            _logger.LogError(ex, "SMTP failure while sending email to {Recipient}. Status: {StatusCode}.", to, ex.StatusCode);
            return;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Unexpected error while sending email to {Recipient}.", to);
            return;
        }
    }
}

internal sealed record SmtpCredentials(string FromName, string Host, int Port, string Username, string Password)
{
    public static SmtpCredentials Load(IConfiguration config)
    {
        var fromName = config["SmtpCredentials:FromName"];
        var host = config["SmtpCredentials:Host"];
        var portRaw = config["SmtpCredentials:Port"];
        var username = config["SmtpCredentials:Username"];
        var password = config["SmtpCredentials:Password"];

        if (string.IsNullOrWhiteSpace(fromName))
            throw new InvalidOperationException("SMTP host is not configured.");

        if (string.IsNullOrWhiteSpace(host))
            throw new InvalidOperationException("SMTP host is not configured.");

        if (!int.TryParse(portRaw, out var port) || port is < 1 or > 65535)
            throw new InvalidOperationException($"SMTP port '{portRaw}' is invalid.");

        if (string.IsNullOrWhiteSpace(username))
            throw new InvalidOperationException("SMTP username is not configured.");

        if (string.IsNullOrWhiteSpace(password))
            throw new InvalidOperationException("SMTP password is not configured.");

        return new(fromName, host, port, username, password);
    }
}