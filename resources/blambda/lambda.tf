locals {
  lambda_tags = merge(var.cluster_tags, {
    lambda = var.lambda_name
  })

  webhook_url = try("${data.terraform_remote_state.cluster[0].outputs.api_deployment.invoke_url}${data.terraform_remote_state.cluster[0].outputs.api_stage.stage_name}${aws_api_gateway_resource.api_resource-{{lambda-name}}[0].path}", null)
}

variable "lambda_workspace" {
  type = string
  default = "lambda-{{cluster}}-{{lambda-name}}"
}

variable "region" {
  type = string
  default = "ap-southeast-1"
}

data "terraform_remote_state" "cluster" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0
  
  backend = "s3"
  config = {
    bucket = "{{tfstate-bucket}}"
    key    = "env:/${var.cluster_workspace}/{{cluster}}/terraform.tfstate"
  }
}

module "runtime-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  source = "./{{tf-module-dir}}"

  layer_name = var.runtime_layer_name
  compatible_architectures = var.runtime_layer_compatible_architectures
  compatible_runtimes = var.runtime_layer_compatible_runtimes
  filename = var.runtime_layer_filename
}

module "deps-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  source = "./{{tf-module-dir}}"

  layer_name = var.deps_layer_name
  compatible_architectures = var.deps_layer_compatible_architectures
  compatible_runtimes = var.deps_layer_compatible_runtimes
  filename = var.deps_layer_filename
}

resource "aws_lambda_function" "lambda-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  function_name = "himmelsstuermer-${local.lambda_tags.cluster}-${var.lambda_name}"
  role = "${aws_iam_role.lambda-{{lambda-name}}[0].arn}"
  handler = var.lambda_handler
  memory_size = var.lambda_memory_size
  source_code_hash = filebase64sha256(var.lambda_filename)
  filename = var.lambda_filename
  runtime = var.lambda_runtime
  architectures = var.lambda_architectures
  timeout = var.lambda_timeout
  layers = [
    module.runtime-{{lambda-name}}[0].arn,
    module.deps-{{lambda-name}}[0].arn,
  ]

  file_system_config {
    arn = aws_efs_access_point.lambda-{{lambda-name}}[0].arn
    local_mount_path = "/mnt/efs"
  }

  vpc_config {
    subnet_ids         = data.terraform_remote_state.cluster[0].outputs.aws_subnet_private[*].id
    security_group_ids = [data.terraform_remote_state.cluster[0].outputs.aws_security_group_lambda_shared.id]
  }

  environment {
    variables = {
  {% for i in lambda-env-vars %}
      {{i.key}} = "{{i.val}}"
  {% endfor %}
    }
  }

  tags = merge(local.lambda_tags, {
    Name = "himmelsstuermer.${local.lambda_tags.cluster}.lambda.${var.lambda_name}"
  })
}

# SQS Queue for the Lambda function
resource "aws_sqs_queue" "lambda_queue-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  name = "himmelsstuermer-${var.cluster_tags.cluster}-sqs-${var.lambda_name}.fifo"

  fifo_queue                  = true
  content_based_deduplication = true
  deduplication_scope         = "messageGroup"
  fifo_throughput_limit       = "perMessageGroupId"
  visibility_timeout_seconds  = 60
  sqs_managed_sse_enabled     = true
  message_retention_seconds   = 1209600  # 14 days

  redrive_policy = jsonencode({
    deadLetterTargetArn = data.terraform_remote_state.cluster[0].outputs.dlq_arn
    maxReceiveCount     = 5
  })

  tags = merge(var.cluster_tags, {
    Name = "himmelsstuermer.${var.cluster_tags.cluster}.sqs.${var.lambda_name}"
  })
}

# SQS Queue Access Policy Document
data "aws_iam_policy_document" "sqs_policy_document-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  statement {
    sid    = "First"
    effect = "Allow"

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.lambda_queue-{{lambda-name}}[0].arn]

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:aws:execute-api:${var.region}:${data.aws_caller_identity.current.account_id}:${data.terraform_remote_state.cluster[0].outputs.api_gateway.id}/*/${aws_api_gateway_method.api_method-{{lambda-name}}[0].http_method}${aws_api_gateway_resource.api_resource-{{lambda-name}}[0].path}"]
    }
  }
}

# SQS Queue Access Policy
resource "aws_sqs_queue_policy" "sqs_policy-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  queue_url = aws_sqs_queue.lambda_queue-{{lambda-name}}[0].id
  policy    = data.aws_iam_policy_document.sqs_policy_document-{{lambda-name}}[0].json
}

# API Gateway Lambda Resource
resource "aws_api_gateway_resource" "api_resource-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  rest_api_id = data.terraform_remote_state.cluster[0].outputs.api_gateway.id
  parent_id   = data.terraform_remote_state.cluster[0].outputs.api_gateway.root_resource_id
  path_part   = "${var.lambda_name}-${var.bot_token}"
}

# API Gateway Lambda Method
resource "aws_api_gateway_method" "api_method-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0
  
  rest_api_id   = data.terraform_remote_state.cluster[0].outputs.api_gateway.id
  resource_id   = aws_api_gateway_resource.api_resource-{{lambda-name}}[0].id
  http_method   = "POST"
  authorization = "NONE"
}

# API Gateway Lambda Method Success Response
resource "aws_api_gateway_method_response" "response_202-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  rest_api_id = data.terraform_remote_state.cluster[0].outputs.api_gateway.id
  resource_id = aws_api_gateway_resource.api_resource-{{lambda-name}}[0].id
  http_method = aws_api_gateway_method.api_method-{{lambda-name}}[0].http_method
  status_code = "202"

  response_models = {
    "application/json" = "Empty"
  }
}

# API Gateway Lambda Method Forbidden Response
resource "aws_api_gateway_method_response" "response_403-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  rest_api_id = data.terraform_remote_state.cluster[0].outputs.api_gateway.id
  resource_id = aws_api_gateway_resource.api_resource-{{lambda-name}}[0].id
  http_method = aws_api_gateway_method.api_method-{{lambda-name}}[0].http_method
  status_code = "403"

  response_models = {
    "application/json" = "Error"
  }
}

# API Gateway Lambda Method Error Response
resource "aws_api_gateway_method_response" "response_500-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  rest_api_id = data.terraform_remote_state.cluster[0].outputs.api_gateway.id
  resource_id = aws_api_gateway_resource.api_resource-{{lambda-name}}[0].id
  http_method = aws_api_gateway_method.api_method-{{lambda-name}}[0].http_method
  status_code = "500"

  response_models = {
    "application/json" = "Error"
  }
}

# API Gateway Lambda Integration
resource "aws_api_gateway_integration" "sqs_integration-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  rest_api_id = data.terraform_remote_state.cluster[0].outputs.api_gateway.id
  resource_id = aws_api_gateway_resource.api_resource-{{lambda-name}}[0].id
  http_method = aws_api_gateway_method.api_method-{{lambda-name}}[0].http_method

  integration_http_method = "POST"
  type                    = "AWS"
  credentials             = data.terraform_remote_state.cluster[0].outputs.api_gateway_sqs_role_arn
  uri                     = "arn:aws:apigateway:${var.region}:sqs:path/${data.aws_caller_identity.current.account_id}/${aws_sqs_queue.lambda_queue-{{lambda-name}}[0].name}"

  request_templates = {
    "application/json" = <<VTL
#set($secretToken = $input.params('X-Telegram-Bot-Api-Secret-Token'))
#set($expectedToken = '${random_string.secret_token-{{lambda-name}}[0].result}')
#if($secretToken != $expectedToken)
  #set($context.responseOverride.status = 403)
  #set($context.responseOverride.header.Content-Type = 'application/json')
#else
Action=SendMessage&MessageBody=$util.urlEncode($input.body)&MessageGroupId=#if($!input.path('$.message.from.id') != "")$input.path('$.message.from.id')#elseif($!input.path('$.callback_query.from.id') != "")$input.path('$.callback_query.from.id')#elseif($!input.path('$.action') != "")action#else unknown#end
#end
VTL
  }

  request_parameters = {
    "integration.request.header.Content-Type" = "'application/x-www-form-urlencoded'"
  }

  passthrough_behavior = "WHEN_NO_TEMPLATES"
  timeout_milliseconds = 29000
}

resource "aws_api_gateway_integration_response" "response_202-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  rest_api_id = data.terraform_remote_state.cluster[0].outputs.api_gateway.id
  resource_id = aws_api_gateway_resource.api_resource-{{lambda-name}}[0].id
  http_method = aws_api_gateway_method.api_method-{{lambda-name}}[0].http_method
  status_code = aws_api_gateway_method_response.response_202-{{lambda-name}}[0].status_code

  selection_pattern = "^2\\d{2}$"

  response_templates = {
    "application/json" = jsonencode({})
  }

  depends_on = [aws_api_gateway_integration.sqs_integration-{{lambda-name}}]
}

resource "aws_api_gateway_integration_response" "response_400-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  rest_api_id = data.terraform_remote_state.cluster[0].outputs.api_gateway.id
  resource_id = aws_api_gateway_resource.api_resource-{{lambda-name}}[0].id
  http_method = aws_api_gateway_method.api_method-{{lambda-name}}[0].http_method
  status_code = aws_api_gateway_method_response.response_500-{{lambda-name}}[0].status_code

  selection_pattern = "^4\\d{2}$"

  response_templates = {
    "application/json" = <<VTL
#if($context.responseOverride.status == "403"){"message":"Invalid secret token"}
#else{"message":"$input.body"}
#end
VTL
  }

  depends_on = [aws_api_gateway_integration.sqs_integration-{{lambda-name}}]
}

resource "aws_api_gateway_integration_response" "response_500-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  rest_api_id = data.terraform_remote_state.cluster[0].outputs.api_gateway.id
  resource_id = aws_api_gateway_resource.api_resource-{{lambda-name}}[0].id
  http_method = aws_api_gateway_method.api_method-{{lambda-name}}[0].http_method
  status_code = aws_api_gateway_method_response.response_500-{{lambda-name}}[0].status_code

  selection_pattern = "^5\\d{2}$"

  response_templates = {
    "application/json" = jsonencode({
      message = "Internal server error"
    })
  }

  depends_on = [aws_api_gateway_integration.sqs_integration-{{lambda-name}}]
}

resource "aws_lambda_event_source_mapping" "sqs_trigger-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  event_source_arn = aws_sqs_queue.lambda_queue-{{lambda-name}}[0].arn
  function_name    = aws_lambda_function.lambda-{{lambda-name}}[0].arn
  
  batch_size       = 10
  maximum_batching_window_in_seconds = 0
  
  scaling_config {
    maximum_concurrency = 1000
  }
}

resource "aws_cloudwatch_log_group" "lambda-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  name = "/aws/lambda/${aws_lambda_function.lambda-{{lambda-name}}[0].function_name}"

  tags = merge(local.lambda_tags, {
    Name = "himmelsstuermer.${local.lambda_tags.cluster}.cw-log-group.lambda.${var.lambda_name}"
  })
}

resource "aws_iam_role" "lambda-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  name = "himmelsstuermer.${local.lambda_tags.cluster}.iam-role.${var.lambda_name}"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Service = "lambda.amazonaws.com"
        }
        Action = "sts:AssumeRole"
      }
    ]
  })

  tags = merge(local.lambda_tags, {
    Name = "himmelsstuermer.${local.lambda_tags.cluster}.iam-role.${var.lambda_name}"
  })
}

resource "aws_iam_policy" "lambda-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  name = "himmelsstuermer.${local.lambda_tags.cluster}.iam-policy.${var.lambda_name}"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = ["${aws_cloudwatch_log_group.lambda-{{lambda-name}}[0].arn}:*"]
      }
    ]
  })

  tags = merge(local.lambda_tags, {
    Name = "himmelsstuermer.${local.lambda_tags.cluster}.iam-policy.${var.lambda_name}"
  })
}

resource "aws_iam_role_policy_attachment" "lambda-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  role = aws_iam_role.lambda-{{lambda-name}}[0].name
  policy_arn = aws_iam_policy.lambda-{{lambda-name}}[0].arn
}

resource "aws_iam_role_policy_attachment" "lambda_vpc_access-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  role       = aws_iam_role.lambda-{{lambda-name}}[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

resource "aws_iam_role_policy_attachment" "lambda_sqs-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  role       = aws_iam_role.lambda-{{lambda-name}}[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaSQSQueueExecutionRole"
}

resource "aws_efs_access_point" "lambda-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  file_system_id = try(data.terraform_remote_state.cluster[0].outputs.aws_efs_file_system_cluster.id, null)

  root_directory {
    path = "/${var.lambda_name}"
    creation_info {
      owner_gid   = 1000
      owner_uid   = 1000
      permissions = "755"
    }
  }

  posix_user {
    gid = 1000
    uid = 1000
  }

  tags = merge(var.cluster_tags, {
    Name = "himmelsstuermer.${var.cluster_tags.cluster}.efs-ap.${var.lambda_name}"
  })
}

resource "aws_iam_role_policy_attachment" "lambda_efs-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0
 
  role       = aws_iam_role.lambda-{{lambda-name}}[0].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonElasticFileSystemClientFullAccess"
}

resource "random_string" "secret_token-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  length  = 32
  special = false

  keepers = {
    timestamp = timestamp()
  }
}

resource "aws_secretsmanager_secret" "bot_secret_token-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  name = "himmelsstuermer-${var.cluster_tags.cluster}-${var.lambda_name}-secret-token"
}

resource "aws_secretsmanager_secret_version" "bot_secret_token-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  secret_id     = aws_secretsmanager_secret.bot_secret_token-{{lambda-name}}[0].id
  secret_string = random_string.secret_token-{{lambda-name}}[0].result
}

resource "aws_iam_role_policy_attachment" "lambda_secrets_manager-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  role       = aws_iam_role.lambda-{{lambda-name}}[0].name
  policy_arn = "arn:aws:iam::aws:policy/SecretsManagerReadWrite"
}

resource "null_resource" "deploy_api-{{lambda-name}}" {
  count = terraform.workspace == var.lambda_workspace ? 1 : 0

  triggers = {
    timestamp = timestamp()
  }

  depends_on = [
    aws_api_gateway_resource.api_resource-{{lambda-name}},
    aws_api_gateway_method.api_method-{{lambda-name}},
    aws_api_gateway_integration.sqs_integration-{{lambda-name}},
    aws_secretsmanager_secret_version.bot_secret_token-{{lambda-name}}
  ]

  provisioner "local-exec" {
    command = <<SHELL
      aws apigateway create-deployment \
        --rest-api-id ${data.terraform_remote_state.cluster[0].outputs.api_gateway.id} \
        --stage-name ${var.cluster_tags.cluster} \
        --description "Deployment triggered by Terraform"

      curl -X POST https://api.telegram.org/bot${var.bot_token}/setWebhook \
        -H "Content-Type: application/json" \
        -d '{
          "url": "${local.webhook_url}",
          "secret_token": "${random_string.secret_token-{{lambda-name}}[0].result}"
        }'
    SHELL

    environment = {
      AWS_ACCESS_KEY_ID     = data.terraform_remote_state.cluster[0].outputs.api_deployer_access_key
      AWS_SECRET_ACCESS_KEY = data.terraform_remote_state.cluster[0].outputs.api_deployer_secret_key
      AWS_DEFAULT_REGION    = var.region
    }
  }
}

# Output API Endpoint (Webhook) 
output "webhook_url" {
  value = local.webhook_url
}
