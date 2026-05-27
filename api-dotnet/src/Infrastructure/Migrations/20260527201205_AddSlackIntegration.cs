using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Infrastructure.Migrations
{
    /// <inheritdoc />
    public partial class AddSlackIntegration : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "IX_Alerts_Deduplication",
                table: "Alerts");

            migrationBuilder.CreateTable(
                name: "SlackIntegrations",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    UserId = table.Column<Guid>(type: "uuid", nullable: false),
                    TeamId = table.Column<string>(type: "text", nullable: false),
                    TeamName = table.Column<string>(type: "text", nullable: false),
                    ChannelId = table.Column<string>(type: "text", nullable: false),
                    ChannelName = table.Column<string>(type: "text", nullable: false),
                    BotAccessToken = table.Column<string>(type: "text", nullable: false),
                    IsActive = table.Column<bool>(type: "boolean", nullable: false),
                    CreatedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
                    UpdatedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_SlackIntegrations", x => x.Id);
                    table.ForeignKey(
                        name: "FK_SlackIntegrations_AspNetUsers_UserId",
                        column: x => x.UserId,
                        principalTable: "AspNetUsers",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_Alerts_Deduplication",
                table: "Alerts",
                columns: new[] { "UserId", "Type", "DomainId", "Channel", "DeduplicationKey" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_SlackIntegrations_UserId_TeamId",
                table: "SlackIntegrations",
                columns: new[] { "UserId", "TeamId" },
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "SlackIntegrations");

            migrationBuilder.DropIndex(
                name: "IX_Alerts_Deduplication",
                table: "Alerts");

            migrationBuilder.CreateIndex(
                name: "IX_Alerts_Deduplication",
                table: "Alerts",
                columns: new[] { "UserId", "Type", "DomainId", "DeduplicationKey" },
                unique: true);
        }
    }
}
